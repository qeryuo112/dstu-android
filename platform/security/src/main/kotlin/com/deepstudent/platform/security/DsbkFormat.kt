package com.deepstudent.platform.security

import java.nio.ByteBuffer
import java.nio.ByteOrder

object DsbkFormat {
    val MAGIC: ByteArray get() = byteArrayOf(0x44, 0x53, 0x42, 0x4b)

    const val VERSION_1 = 1
    const val VERSION_2 = 2
    const val V1_HEADER_LEN = 45
    const val V2_HEADER_LEN = 44
    const val GCM_TAG_LEN = 16
    const val V2_CHUNK_OFFSET = V2_HEADER_LEN - 4
    const val SALT_LEN = 16
    const val V1_NONCE_LEN = 12
    const val V2_PREFIX_LEN = 7
    const val STREAM_CHUNK = 1024 * 1024
    const val MAX_STREAM_CHUNK = 64 * 1024 * 1024
    const val KEY_LEN = 32

    const val DEFAULT_MEMORY_KIB = 65536L
    const val DEFAULT_ITERATIONS = 3L
    const val DEFAULT_PARALLELISM = 4L
    const val MAX_MEMORY_KIB = 1024L * 1024L
    const val MAX_ITERATIONS = 16L
    const val MAX_PARALLELISM = 8L

    fun isEncryptedBackup(bytes: ByteArray): Boolean = hasMagic(bytes, 0)

    fun hasMagic(bytes: ByteArray, offset: Int): Boolean {
        if (offset < 0 || bytes.size - offset < MAGIC.size) return false
        return MAGIC.indices.all { bytes[offset + it] == MAGIC[it] }
    }
}

data class DsbkKdfParams(
    val memoryKiB: Long,
    val iterations: Long,
    val parallelism: Long,
) {
    init {
        require(memoryKiB >= 0 && iterations >= 0 && parallelism >= 0) {
            "KDF parameters must not be negative"
        }
    }

    fun validateBudget() {
        if (
            memoryKiB > DsbkFormat.MAX_MEMORY_KIB ||
            iterations > DsbkFormat.MAX_ITERATIONS ||
            parallelism > DsbkFormat.MAX_PARALLELISM
        ) {
            throw DsbkException(DsbkError.KDF_BUDGET_EXCEEDED)
        }
    }

    fun validateForDerivation() {
        if (
            memoryKiB < 8L ||
            memoryKiB < parallelism * 8L ||
            iterations < 1L ||
            parallelism < 1L
        ) {
            throw DsbkException(DsbkError.KDF_FAILED)
        }
    }

    companion object {
        val PRODUCTION = DsbkKdfParams(
            DsbkFormat.DEFAULT_MEMORY_KIB,
            DsbkFormat.DEFAULT_ITERATIONS,
            DsbkFormat.DEFAULT_PARALLELISM,
        )
    }
}

internal data class DsbkV1Header(
    val params: DsbkKdfParams,
    val salt: ByteArray,
    val nonce: ByteArray,
)

internal data class DsbkV2Header(
    val params: DsbkKdfParams,
    val salt: ByteArray,
    val prefix: ByteArray,
    val chunkSize: Int,
)

internal object DsbkHeaderCodec {
    fun parseV1(header: ByteArray): DsbkV1Header {
        if (header.size < DsbkFormat.V1_HEADER_LEN || !DsbkFormat.hasMagic(header, 0)) {
            throw DsbkException(DsbkError.HEADER_INVALID)
        }
        if (header[4].toInt() != DsbkFormat.VERSION_1) {
            throw DsbkException(DsbkError.VERSION_UNSUPPORTED)
        }
        return DsbkV1Header(
            params = params(header),
            salt = header.copyOfRange(17, 33),
            nonce = header.copyOfRange(33, 45),
        )
    }

    fun parseV2(header: ByteArray): DsbkV2Header {
        if (header.size < DsbkFormat.V2_HEADER_LEN || !DsbkFormat.hasMagic(header, 0)) {
            throw DsbkException(DsbkError.HEADER_INVALID)
        }
        if (header[4].toInt() != DsbkFormat.VERSION_2) {
            throw DsbkException(DsbkError.VERSION_UNSUPPORTED)
        }
        val chunk = readUInt32LE(header, DsbkFormat.V2_CHUNK_OFFSET)
        if (chunk == 0L || chunk > DsbkFormat.MAX_STREAM_CHUNK.toLong()) {
            throw DsbkException(DsbkError.CHUNK_INVALID)
        }
        return DsbkV2Header(
            params = params(header),
            salt = header.copyOfRange(17, 33),
            prefix = header.copyOfRange(33, 40),
            chunkSize = chunk.toInt(),
        )
    }

    fun writeV1(params: DsbkKdfParams, salt: ByteArray, nonce: ByteArray): ByteArray {
        require(salt.size == DsbkFormat.SALT_LEN)
        require(nonce.size == DsbkFormat.V1_NONCE_LEN)
        val header = ByteArray(DsbkFormat.V1_HEADER_LEN)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(DsbkFormat.MAGIC)
        buffer.put(DsbkFormat.VERSION_1.toByte())
        buffer.putInt(params.memoryKiB.toInt())
        buffer.putInt(params.iterations.toInt())
        buffer.putInt(params.parallelism.toInt())
        buffer.put(salt)
        buffer.put(nonce)
        return header
    }

    fun writeV2(params: DsbkKdfParams, salt: ByteArray, prefix: ByteArray): ByteArray {
        require(salt.size == DsbkFormat.SALT_LEN)
        require(prefix.size == DsbkFormat.V2_PREFIX_LEN)
        val header = ByteArray(DsbkFormat.V2_HEADER_LEN)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(DsbkFormat.MAGIC)
        buffer.put(DsbkFormat.VERSION_2.toByte())
        buffer.putInt(params.memoryKiB.toInt())
        buffer.putInt(params.iterations.toInt())
        buffer.putInt(params.parallelism.toInt())
        buffer.put(salt)
        buffer.put(prefix)
        buffer.putInt(DsbkFormat.STREAM_CHUNK)
        return header
    }

    fun chunkNonce(prefix: ByteArray, counter: Long, isFinal: Boolean): ByteArray {
        require(prefix.size == DsbkFormat.V2_PREFIX_LEN)
        require(counter in 0..0xffffffffL)
        val nonce = ByteArray(DsbkFormat.V1_NONCE_LEN)
        prefix.copyInto(nonce, 0, 0, DsbkFormat.V2_PREFIX_LEN)
        ByteBuffer.wrap(nonce, 7, 4).order(ByteOrder.BIG_ENDIAN).putInt(counter.toInt())
        nonce[11] = if (isFinal) 1 else 0
        return nonce
    }

    fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL

    private fun params(header: ByteArray): DsbkKdfParams = DsbkKdfParams(
        memoryKiB = readUInt32LE(header, 5),
        iterations = readUInt32LE(header, 9),
        parallelism = readUInt32LE(header, 13),
    )
}
