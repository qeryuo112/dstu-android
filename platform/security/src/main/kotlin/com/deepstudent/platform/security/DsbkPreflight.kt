package com.deepstudent.platform.security

import java.io.InputStream

/** Unauthenticated metadata only; a successful preflight never verifies a password or content. */
data class DsbkPreflight(
    val version: Int,
    val kdf: DsbkKdfParams,
    val chunkBytes: Int?,
    val containerBytes: Long?,
    val plaintextBytes: Long?,
)

internal data class DsbkParsedHeader(
    val info: DsbkPreflight,
    val v1: DsbkV1Header? = null,
    val v2: DsbkV2Header? = null,
)

internal object DsbkPreflightReader {
    private const val MAX_CHUNKS = 0x1_0000_0000L

    fun read(
        input: InputStream,
        containerBytes: Long?,
        maxOutputBytes: Long,
        maxInputBytes: Long,
        maxMemoryKiB: Long,
        maxChunkBytes: Int,
    ): DsbkParsedHeader {
        validateLengths(containerBytes, maxOutputBytes, maxInputBytes)
        val bounded = DsbkInput(input, maxInputBytes)
        val head = DsbkIo.readExact(bounded, 5)
        if (!DsbkFormat.hasMagic(head, 0)) throw DsbkException(DsbkError.HEADER_INVALID)
        return when (head[4].toInt() and 0xff) {
            DsbkFormat.VERSION_1 -> readV1(bounded, head, containerBytes, maxOutputBytes, maxMemoryKiB)
            DsbkFormat.VERSION_2 -> readV2(bounded, head, containerBytes, maxOutputBytes, maxMemoryKiB, maxChunkBytes)
            else -> throw DsbkException(DsbkError.VERSION_UNSUPPORTED)
        }
    }

    private fun readV1(
        input: InputStream,
        head: ByteArray,
        containerBytes: Long?,
        maxOutputBytes: Long,
        maxMemoryKiB: Long,
    ): DsbkParsedHeader {
        val header = DsbkHeaderCodec.parseV1(head + DsbkIo.readExact(input, DsbkFormat.V1_HEADER_LEN - 5))
        validateKdf(header.params, maxMemoryKiB)
        val plaintext = containerBytes?.let { length ->
            val body = bodyLength(length, DsbkFormat.V1_HEADER_LEN)
            val size = body - DsbkFormat.GCM_TAG_LEN
            if (size > DsbkCrypto.MAX_IN_MEMORY_BYTES) throw DsbkException(DsbkError.V1_CONVERSION_REQUIRED)
            if (size > maxOutputBytes) throw DsbkException(DsbkError.OUTPUT_LIMIT_EXCEEDED)
            DsbkMemoryBudget.requireAvailable(header.params, reserveBytes = body * DsbkMemoryBudget.V1_ARRAY_FACTOR)
            size
        }
        if (plaintext == null) DsbkMemoryBudget.requireAvailable(header.params)
        return DsbkParsedHeader(DsbkPreflight(1, header.params, null, containerBytes, plaintext), v1 = header)
    }

    private fun readV2(
        input: InputStream,
        head: ByteArray,
        containerBytes: Long?,
        maxOutputBytes: Long,
        maxMemoryKiB: Long,
        maxChunkBytes: Int,
    ): DsbkParsedHeader {
        val header = DsbkHeaderCodec.parseV2(head + DsbkIo.readExact(input, DsbkFormat.V2_HEADER_LEN - 5))
        if (header.chunkSize > maxChunkBytes) throw DsbkException(DsbkError.CHUNK_INVALID)
        validateKdf(header.params, maxMemoryKiB)
        val plaintext = containerBytes?.let { plaintextLengthV2(it, header.chunkSize, maxOutputBytes) }
        val buffer = minOf(header.chunkSize.toLong(), maxOutputBytes) + DsbkFormat.GCM_TAG_LEN
        DsbkMemoryBudget.requireAvailable(header.params, reserveBytes = buffer * DsbkMemoryBudget.V2_ARRAY_FACTOR)
        return DsbkParsedHeader(DsbkPreflight(2, header.params, header.chunkSize, containerBytes, plaintext), v2 = header)
    }

    private fun plaintextLengthV2(length: Long, chunkSize: Int, maxOutputBytes: Long): Long {
        val body = bodyLength(length, DsbkFormat.V2_HEADER_LEN)
        val chunk = chunkSize.toLong() + DsbkFormat.GCM_TAG_LEN
        val full = body / chunk
        val remainder = body % chunk
        if (remainder in 1 until DsbkFormat.GCM_TAG_LEN.toLong()) {
            throw DsbkException(DsbkError.AUTHENTICATION_FAILED)
        }
        val chunks = full + if (remainder == 0L) 0 else 1
        if (chunks > MAX_CHUNKS) throw DsbkException(DsbkError.CHUNK_INVALID)
        val size = body - chunks * DsbkFormat.GCM_TAG_LEN
        if (size > maxOutputBytes) throw DsbkException(DsbkError.OUTPUT_LIMIT_EXCEEDED)
        return size
    }

    private fun validateLengths(containerBytes: Long?, maxOutputBytes: Long, maxInputBytes: Long) {
        if (maxOutputBytes < 0) throw DsbkException(DsbkError.OUTPUT_LIMIT_EXCEEDED)
        if (maxInputBytes < 0 || containerBytes != null && containerBytes < 0) {
            throw DsbkException(DsbkError.INPUT_LIMIT_EXCEEDED)
        }
        if (containerBytes != null && containerBytes > maxInputBytes) {
            throw DsbkException(DsbkError.INPUT_LIMIT_EXCEEDED)
        }
    }

    private fun validateKdf(params: DsbkKdfParams, maxMemoryKiB: Long) {
        params.validateBudget()
        if (params.memoryKiB > maxMemoryKiB) throw DsbkException(DsbkError.KDF_BUDGET_EXCEEDED)
        params.validateForDerivation()
    }

    private fun bodyLength(length: Long, headerBytes: Int): Long {
        if (length < headerBytes) throw DsbkException(DsbkError.HEADER_INVALID)
        val body = length - headerBytes
        if (body == 0L) throw DsbkException(DsbkError.DATA_MISSING)
        if (body < DsbkFormat.GCM_TAG_LEN) throw DsbkException(DsbkError.AUTHENTICATION_FAILED)
        return body
    }
}
