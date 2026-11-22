package com.deepstudent.services.resources.data.saf

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.deepstudent.services.resources.api.DocumentCancellation
import com.deepstudent.services.resources.api.DocumentWriteStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

private const val POLL_MILLIS = 100

/**
 * Opens only read-only/write-only descriptors, including non-seekable provider pipes. Nonblocking
 * poll + bounded chunks permit the transfer owner to cancel even while a provider stalls its pipe.
 */
internal fun openSafInput(resolver: ContentResolver, uri: Uri, token: DocumentCancellation): InputStream =
    SafInput(openDescriptor(resolver, uri, "r", token), token)

internal fun openSafOutput(resolver: ContentResolver, uri: Uri, token: DocumentCancellation): DocumentWriteStream =
    SafOutput(openDescriptor(resolver, uri, "w", token), token)

private fun openDescriptor(
    resolver: ContentResolver, uri: Uri, mode: String, token: DocumentCancellation,
): ParcelFileDescriptor {
    token.check()
    val signal = CancellationSignal()
    val registration = token.onCancel { signal.cancel() }
    val pfd = try {
        resolver.openFileDescriptor(uri, mode, signal) ?: throw FileNotFoundException("provider returned no descriptor")
    } catch (failure: Exception) {
        token.check()
        throw failure
    } finally {
        registration.close()
    }
    try {
        token.check()
        val fd = pfd.fileDescriptor
        val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
        Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        return pfd
    } catch (failure: Exception) {
        pfd.close()
        token.check()
        throw IOException("descriptor setup failed", failure)
    }
}

private class SafInput(private val pfd: ParcelFileDescriptor, private val token: DocumentCancellation) : InputStream() {
    private val single = ByteArray(1)
    private var closed = false
    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        bounds(buffer, offset, length)
        if (length == 0) return 0
        checkOpen(closed)
        while (true) {
            waitFor(pfd, OsConstants.POLLIN, token)
            try {
                val read = Os.read(pfd.fileDescriptor, buffer, offset, minOf(length, 16 * 1024))
                if (read != 0) return read
                checkReadCompletion(pfd)
                return -1
            } catch (error: ErrnoException) {
                if (!error.retryable()) throw IOException("provider read failed", error)
            }
        }
    }
    override fun close() { if (!closed) { closed = true; pfd.close() } }
}

private class SafOutput(private val pfd: ParcelFileDescriptor, private val token: DocumentCancellation) : DocumentWriteStream() {
    private val single = ByteArray(1)
    private var closed = false
    override fun write(value: Int) { single[0] = value.toByte(); write(single, 0, 1) }
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        bounds(buffer, offset, length)
        checkOpen(closed)
        var done = 0
        while (done < length) {
            waitFor(pfd, OsConstants.POLLOUT, token)
            try {
                val written = Os.write(pfd.fileDescriptor, buffer, offset + done, minOf(length - done, 16 * 1024))
                if (written <= 0) throw IOException("provider made no write progress")
                done += written
            } catch (error: ErrnoException) {
                if (!error.retryable()) throw IOException("provider write failed", error)
            }
        }
    }
    override fun close() { if (!closed) { closed = true; pfd.close() } }
    override fun abort() {
        if (!closed) {
            closed = true
            if (pfd.canDetectErrors()) pfd.closeWithError("transfer aborted") else pfd.close()
        }
    }
}

private fun checkReadCompletion(pfd: ParcelFileDescriptor) {
    if (pfd.canDetectErrors()) pfd.checkError()
}

private fun waitFor(pfd: ParcelFileDescriptor, events: Int, token: DocumentCancellation) {
    val poll = StructPollfd().apply { fd = pfd.fileDescriptor; this.events = events.toShort() }
    while (true) {
        token.check()
        try {
            if (Os.poll(arrayOf(poll), POLL_MILLIS) == 0) continue
            if (poll.revents.toInt() and OsConstants.POLLNVAL != 0) throw IOException("provider descriptor invalid")
            // HUP/ERR must be handed to read/write, where EOF/checkError or EPIPE is observable.
            return
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EINTR) throw IOException("provider poll failed", error)
        }
    }
}

private fun ErrnoException.retryable() = errno == OsConstants.EAGAIN || errno == OsConstants.EINTR
private fun checkOpen(closed: Boolean) { if (closed) throw IOException("descriptor closed") }
private fun bounds(buffer: ByteArray, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
}
