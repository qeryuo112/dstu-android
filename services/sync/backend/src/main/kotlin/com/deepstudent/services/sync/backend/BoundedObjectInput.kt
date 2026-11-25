package com.deepstudent.services.sync.backend

import com.deepstudent.services.sync.api.SyncError
import java.io.InputStream

/** Checks cancellation between reads and rejects a stream that lies about its size. */
class BoundedObjectInput(private val source: InputStream, private val limit: Long, private val check: () -> Unit) : InputStream() {
    init {
        require(limit in 0 until Long.MAX_VALUE)
    }

    var count = 0L
        private set

    override fun read(): Int {
        check()
        val byte = source.read()
        if (byte >= 0) advance(1)
        return byte
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        check()
        val read = source.read(bytes, offset, minOf(length.toLong(), limit - count + 1).toInt())
        if (read > 0) advance(read)
        return read
    }

    private fun advance(bytes: Int) {
        count += bytes.toLong()
        if (count > limit) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
    }

    override fun close() = source.close()
}
