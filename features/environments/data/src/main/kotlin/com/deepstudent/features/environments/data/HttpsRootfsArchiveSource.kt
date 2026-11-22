package com.deepstudent.features.environments.data

import com.deepstudent.features.environments.api.EnvironmentCatalog
import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.RootfsArchiveSource
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection

/**
 * The one approved remote source, not a general URL downloader. Constructing it performs no I/O;
 * the environment worker opens it only after the user explicitly submits an installation.
 */
class HttpsRootfsArchiveSource internal constructor(
    private val connect: (URL) -> HttpsURLConnection,
    private val expectedBytes: Long = EnvironmentCatalog.UbuntuNobleAarch64.archiveBytes,
) : RootfsArchiveSource {
    constructor() : this({ url -> url.openConnection() as HttpsURLConnection })

    init {
        require(expectedBytes > 0)
    }

    override fun open(): InputStream {
        val connection = try {
            connect(URL(FROZEN_URL))
        } catch (failure: Exception) {
            EnvironmentDataLog.failure("download-connect-failed", failure)
            throw downloadFailure(failure)
        }
        var body: InputStream? = null
        try {
            configureConnection(connection)
            validateResponse(connection, expectedBytes)
            body = connection.inputStream
            EnvironmentDataLog.event("download-opened", "expectedBytes=$expectedBytes")
            return BoundedConnectionStream(body, connection, expectedBytes)
        } catch (failure: Throwable) {
            closeFailedConnection(connection, body)
            EnvironmentDataLog.failure("download-open-failed", failure)
            propagateDownloadFailure(failure)
        }
    }
}

/** Owns both response body and connection; rejects premature EOF and any extra byte. */
private class BoundedConnectionStream(
    private val body: InputStream,
    private val connection: HttpsURLConnection,
    private var remaining: Long,
) : InputStream() {
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        validateRead(buffer.size, offset, length)
        if (length == 0) return 0
        return try {
            readBounded(buffer, offset, length)
        } catch (failure: Throwable) {
            try { close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            EnvironmentDataLog.failure("download-read-failed", failure)
            propagateDownloadFailure(failure)
        }
    }

    private fun validateRead(size: Int, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > size - length) throw IndexOutOfBoundsException()
        if (closed) throw IOException("rootfs download stream is closed")
    }

    private fun readBounded(buffer: ByteArray, offset: Int, length: Int): Int {
        // Read one byte beyond the remaining budget to detect an oversized chunked body.
        val limit = minOf(length.toLong(), remaining + 1).toInt()
        val count = body.read(buffer, offset, limit)
        if ((count < 0 && remaining != 0L) || count.toLong() > remaining) {
            throw archiveFailure(EnvironmentFailureCode.INVALID_ARCHIVE, "rootfs download length mismatch")
        }
        if (count > 0) remaining -= count
        return count
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val buffer = ByteArray(minOf(n, 8192).toInt())
        var skipped = 0L
        while (skipped < n) {
            val count = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
            if (count < 0) break
            skipped += count
        }
        return skipped
    }

    override fun markSupported(): Boolean = false
    override fun reset(): Nothing = throw IOException("mark/reset is not supported")

    override fun close() {
        if (closed) return
        closed = true
        try {
            body.close()
        } finally {
            connection.disconnect()
            EnvironmentDataLog.event("download-closed")
        }
    }
}

private fun configureConnection(connection: HttpsURLConnection) {
    connection.instanceFollowRedirects = false
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS
    connection.requestMethod = "GET"
    connection.setRequestProperty("Accept-Encoding", "identity")
}

private fun validateResponse(connection: HttpsURLConnection, expectedBytes: Long) {
    val status = connection.responseCode
    if (status != HttpURLConnection.HTTP_OK) {
        EnvironmentDataLog.event("download-status-rejected", "status=$status")
        throw archiveFailure(EnvironmentFailureCode.ARCHIVE_MISSING, "rootfs download unavailable")
    }
    val encoding = connection.contentEncoding
    if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
        throw archiveFailure(EnvironmentFailureCode.INVALID_ARCHIVE, "unexpected content encoding")
    }
    // Content-Length can be absent; the stream is counted independently in all cases.
    val length = connection.getHeaderField("Content-Length")
    if (length != null && length.toLongOrNull() != expectedBytes) {
        throw archiveFailure(EnvironmentFailureCode.INVALID_ARCHIVE, "rootfs download length mismatch")
    }
}

private fun closeFailedConnection(connection: HttpsURLConnection, body: InputStream?) {
    closeIgnoringFailure(body)
    try { closeIgnoringFailure(connection.errorStream) } catch (_: Exception) { }
    try { connection.disconnect() } catch (_: Exception) { }
}

private fun propagateDownloadFailure(failure: Throwable): Nothing = when (failure) {
    is CancellationException -> throw failure
    is EnvironmentCommandException -> throw failure
    is Exception -> throw downloadFailure(failure)
    else -> throw failure
}

private fun closeIgnoringFailure(stream: InputStream?) {
    try { stream?.close() } catch (_: Exception) { }
}

private fun downloadFailure(cause: Exception): EnvironmentCommandException {
    if (cause is CancellationException) throw cause
    return archiveFailure(EnvironmentFailureCode.ARCHIVE_MISSING, "rootfs download unavailable", cause)
}

private fun archiveFailure(
    code: EnvironmentFailureCode,
    message: String,
    cause: Throwable? = null,
): EnvironmentCommandException = EnvironmentCommandException(EnvironmentFailure(code, message, true), cause)

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000
private const val FROZEN_URL = "https://raw.githubusercontent.com/AAswordman/OperitTerminalCore/" +
    "e4646680663789002b43bbfffa6e69e65c35e614/src/main/assets/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
