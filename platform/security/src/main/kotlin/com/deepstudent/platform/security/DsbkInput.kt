package com.deepstudent.platform.security

import java.io.InputStream

/** Limits bytes read from an untrusted stream, even if the caller grants a large output budget. */
internal class DsbkInput(
    private val source: InputStream,
    private val limit: Long,
) : InputStream() {
    private var consumed = 0L

    init {
        if (limit < 0) throw DsbkException(DsbkError.INPUT_LIMIT_EXCEEDED)
    }

    override fun read(): Int {
        checkInterrupted()
        val value = source.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        if (length == 0) return 0
        checkInterrupted()
        // A single byte beyond the limit is needed to distinguish exact EOF.
        if (consumed == limit) {
            val value = read()
            if (value < 0) return -1
        }
        val count = source.read(bytes, offset, minOf(length.toLong(), limit - consumed).toInt())
        if (count > 0) account(count)
        return count
    }

    private fun account(count: Int) {
        if (count.toLong() > limit - consumed) throw DsbkException(DsbkError.INPUT_LIMIT_EXCEEDED)
        consumed += count
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw DsbkException(DsbkError.IO_FAILED)
    }
}

internal object DsbkIo {
    fun readExact(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        val filled = fill(input, bytes, -1, size)
        if (filled != size) {
            bytes.wipe()
            throw DsbkException(DsbkError.HEADER_INVALID)
        }
        return bytes
    }

    /** A zero-length bulk read must make progress rather than spin indefinitely. */
    fun fill(input: InputStream, buffer: ByteArray, first: Int, limit: Int): Int {
        var filled = 0
        if (first >= 0) buffer[filled++] = first.toByte()
        while (filled < limit) {
            val read = input.read(buffer, filled, limit - filled)
            if (read < 0) break
            if (read > 0) {
                filled += read
            } else {
                val byte = input.read()
                if (byte < 0) break
                buffer[filled++] = byte.toByte()
            }
        }
        return filled
    }

    fun readAllBounded(input: InputStream, maxBytes: Int, exceeded: DsbkError): ByteArray {
        val sink = ByteArrayDsbkSink(maxBytes.toLong())
        val buffer = ByteArray(8192)
        try {
            copyBounded(input, sink, buffer, maxBytes, exceeded)
            sink.commit()
            return sink.bytes()
        } finally {
            buffer.wipe()
            sink.abort()
        }
    }
    private fun copyBounded(input: InputStream, sink: DsbkSink, buffer: ByteArray, maxBytes: Int, exceeded: DsbkError) {
        while (true) {
            val limit = minOf(buffer.size.toLong(), maxBytes - sink.stagedBytes).toInt()
            val count = fill(input, buffer, -1, limit)
            if (count > 0) sink.write(buffer, 0, count)
            if (count < buffer.size) {
                if (input.read() >= 0) throw DsbkException(exceeded)
                return
            }
        }
    }

}
