package com.deepstudent.platform.security

internal class ByteArrayDsbkSink(
    private val limit: Long,
) : DsbkSink {
    private var committed = false
    private var aborted = false
    private var size = 0

    internal var storage: ByteArray = ByteArray(0)
        private set

    init {
        require(limit >= 0) { "sink budget must not be negative" }
        storage = ByteArray(minOf(limit, INITIAL_BYTES.toLong()).toInt())
    }

    override var stagedBytes: Long = 0
        private set

    @Synchronized
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (committed || aborted) throw DsbkException(DsbkError.STAGING_FAILED)
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw DsbkException(DsbkError.STAGING_FAILED)
        }
        if (length.toLong() > limit - stagedBytes || length > Int.MAX_VALUE - size) {
            throw DsbkException(DsbkError.OUTPUT_LIMIT_EXCEEDED)
        }
        ensureCapacity(size + length)
        bytes.copyInto(storage, size, offset, offset + length)
        size += length
        stagedBytes += length
    }

    @Synchronized
    override fun commit() {
        if (aborted) throw DsbkException(DsbkError.STAGING_FAILED)
        committed = true
    }

    @Synchronized
    override fun abort() {
        if (aborted) return
        aborted = true
        storage.wipe()
        storage = ByteArray(0)
        size = 0
        stagedBytes = 0
    }

    @Synchronized
    fun bytes(): ByteArray {
        if (!committed || aborted) throw DsbkException(DsbkError.STAGING_FAILED)
        val result = storage.copyOf(size)
        storage.wipe()
        storage = ByteArray(0)
        size = 0
        aborted = true
        return result
    }

    private fun ensureCapacity(required: Int) {
        if (required <= storage.size) return
        val doubled = maxOf(INITIAL_BYTES.toLong(), storage.size * 2L)
        val grown = maxOf(required.toLong(), minOf(limit, doubled, Int.MAX_VALUE.toLong())).toInt()
        val next = ByteArray(grown)
        storage.copyInto(next, 0, 0, size)
        storage.wipe()
        storage = next
    }

    companion object {
        private const val INITIAL_BYTES = 8192
    }
}
