package com.deepstudent.services.sync.data

import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import okhttp3.Response
import okio.Buffer

internal fun boundedDavBody(response: Response, limit: Int): ByteArray {
    val body = response.body ?: return ByteArray(0)
    if (body.contentLength() > limit) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
    val source = body.source()
    val buffer = Buffer()
    while (source.read(buffer, minOf(8192L, limit + 1L - buffer.size)) != -1L) {
        if (buffer.size > limit) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
    }
    return buffer.readByteArray()
}

internal fun readDavPrefix(response: Response, bytes: Int, size: Long): ByteArray {
    val body = response.body ?: invalidProofRead()
    requirePrefixHeaders(response, bytes, size, body.contentLength())
    val source = body.source()
    if (!source.request(bytes.toLong())) invalidProofRead()
    val prefix = source.readByteArray(bytes.toLong())
    if ((response.code == 206 || size == bytes.toLong()) && !source.exhausted()) invalidProofRead()
    return prefix
}

private fun requirePrefixHeaders(response: Response, bytes: Int, size: Long, length: Long) {
    val expectedLength = when (response.code) {
        200 -> size
        206 -> {
            if (response.header("Content-Range") != "bytes 0-${bytes - 1}/$size") invalidProofRead()
            bytes.toLong()
        }
        else -> invalidProofRead()
    }
    if (length != -1L && length != expectedLength) invalidProofRead()
}

internal fun Response.strongTag(): String? = header("ETag")?.takeIf {
    it.length in 2..512 && it.first() == '"' && it.last() == '"' && it.drop(1).dropLast(1).none { c -> c <= ' ' || c == '"' || c == '\u007f' }
}

internal data class DavClaimSnapshot(val tag: String?)

internal fun readClaimSnapshot(response: Response, expected: ByteArray): DavClaimSnapshot? {
    val bytes = boundedDavBody(response, 64 * 1024)
    return if (bytes.contentEquals(expected)) DavClaimSnapshot(response.strongTag()) else null
}

private fun invalidProofRead(): Nothing = throw SyncFailure(SyncError.ENCRYPTED_DATA_INVALID)
