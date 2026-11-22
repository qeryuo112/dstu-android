package com.deepstudent.platform.security

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

internal fun interface RandomBytesSource {
    fun next(length: Int): ByteArray
}

internal object SecureRandomBytes : RandomBytesSource {
    private val random = SecureRandom()

    override fun next(length: Int): ByteArray = ByteArray(length).also { random.nextBytes(it) }
}

internal class DsbkEngine(
    private val deriver: DsbkKeyDeriver,
    private val random: RandomBytesSource = SecureRandomBytes,
    private val maxMemoryKiB: Long = DsbkFormat.MAX_MEMORY_KIB,
    private val maxChunkBytes: Int = DsbkFormat.MAX_STREAM_CHUNK,
) {
    init {
        require(maxMemoryKiB > 0) { "KDF memory budget must be positive" }
        require(maxChunkBytes in 1..DsbkFormat.MAX_STREAM_CHUNK) {
            "stream chunk budget is invalid"
        }
    }

    fun newSalt(): ByteArray = random.next(DsbkFormat.SALT_LEN)

    fun newNonce(): ByteArray = random.next(DsbkFormat.V1_NONCE_LEN)

    fun newPrefix(): ByteArray = random.next(DsbkFormat.V2_PREFIX_LEN)

    fun encryptV1(
        plaintext: ByteArray,
        password: CharArray,
        salt: ByteArray,
        params: DsbkKdfParams,
        nonce: ByteArray,
    ): ByteArray = synchronized(DsbkMemoryBudget.operationGate) {
        if (plaintext.size.toLong() > DsbkCrypto.MAX_IN_MEMORY_BYTES) {
            throw DsbkException(DsbkError.INPUT_LIMIT_EXCEEDED)
        }
        validateParams(params)
        DsbkMemoryBudget.requireAvailable(
            params,
            reserveBytes = (plaintext.size.toLong() + DsbkFormat.GCM_TAG_LEN) * DsbkMemoryBudget.V2_ARRAY_FACTOR,
        )
        val key = deriveKey(password, salt, params)
        return try {
            val header = DsbkHeaderCodec.writeV1(params, salt, nonce)
            header + AesGcm.seal(key, nonce, plaintext)
        } catch (error: DsbkException) {
            throw error
        } catch (_: Exception) {
            throw DsbkException(DsbkError.CRYPTO_FAILED)
        } finally {
            key.wipe()
        }
    }

    fun encryptV2(
        input: InputStream,
        output: OutputStream,
        password: CharArray,
        salt: ByteArray,
        params: DsbkKdfParams,
        prefix: ByteArray,
        maxInputBytes: Long = DsbkCrypto.DEFAULT_MAX_INPUT_BYTES,
    ) = synchronized(DsbkMemoryBudget.operationGate) {
        val bounded = DsbkInput(input, maxInputBytes)
        validateParams(params)
        DsbkMemoryBudget.requireAvailable(
            params,
            reserveBytes = (DsbkFormat.STREAM_CHUNK.toLong() + DsbkFormat.GCM_TAG_LEN) * DsbkMemoryBudget.V2_ARRAY_FACTOR,
        )
        val plaintext = ByteArray(DsbkFormat.STREAM_CHUNK)
        val key = try {
            deriveKey(password, salt, params)
        } catch (error: Exception) {
            plaintext.wipe()
            throw error
        }
        try {
            output.write(DsbkHeaderCodec.writeV2(params, salt, prefix))
            var counter = 0L
            var size = DsbkIo.fill(bounded, plaintext, -1, DsbkFormat.STREAM_CHUNK)
            while (true) {
                val lookahead = bounded.read()
                val final = lookahead == -1
                val ciphertext = AesGcm.seal(
                    key,
                    DsbkHeaderCodec.chunkNonce(prefix, counter, final),
                    plaintext,
                    0,
                    size,
                )
                output.write(ciphertext)
                ciphertext.wipe()
                plaintext.wipe()
                if (final) break
                if (counter == 0xffffffffL) throw DsbkException(DsbkError.CHUNK_INVALID)
                size = DsbkIo.fill(bounded, plaintext, lookahead, DsbkFormat.STREAM_CHUNK)
                counter++
            }
            output.flush()
        } catch (error: DsbkException) {
            throw error
        } catch (_: IOException) {
            throw DsbkException(DsbkError.IO_FAILED)
        } catch (_: Exception) {
            throw DsbkException(DsbkError.CRYPTO_FAILED)
        } finally {
            plaintext.wipe()
            key.wipe()
        }
    }

    fun decrypt(
        input: InputStream,
        password: CharArray,
        sink: DsbkSink,
        maxOutputBytes: Long,
        maxInputBytes: Long = DsbkCrypto.DEFAULT_MAX_CONTAINER_BYTES,
        trace: SecurityTrace? = null,
    ) = synchronized(DsbkMemoryBudget.operationGate) {
        try {
            val bounded = DsbkInput(input, maxInputBytes)
            val header = DsbkPreflightReader.read(
                bounded, null, maxOutputBytes, maxInputBytes, maxMemoryKiB, maxChunkBytes,
            )
            when (header.info.version) {
                DsbkFormat.VERSION_1 -> {
                    trace?.start(SecurityPhase.V1_DECRYPT)
                    decryptV1(bounded, checkNotNull(header.v1), password, sink, maxOutputBytes)
                }
                DsbkFormat.VERSION_2 -> {
                    trace?.start(SecurityPhase.V2_DECRYPT)
                    decryptV2(bounded, checkNotNull(header.v2), password, sink, maxOutputBytes)
                }
            }
            trace?.start(SecurityPhase.COMMIT)
            sink.commit()
        } catch (error: DsbkException) {
            sink.abortQuietly()
            throw error
        } catch (_: OutOfMemoryError) {
            sink.abortQuietly()
            throw DsbkException(DsbkError.MEMORY_BUDGET_EXCEEDED)
        } catch (_: IOException) {
            sink.abortQuietly()
            throw DsbkException(DsbkError.IO_FAILED)
        } catch (_: Exception) {
            sink.abortQuietly()
            throw DsbkException(DsbkError.STAGING_FAILED)
        }
    }

    private fun decryptV1(
        input: InputStream,
        header: DsbkV1Header,
        password: CharArray,
        sink: DsbkSink,
        maxOutputBytes: Long,
    ) {
        // Admission happens before either the bounded read or key derivation.
        val heapCap = DsbkMemoryBudget.v1PlaintextCapacity(header.params, maxOutputBytes)
        val plainCap = minOf(maxOutputBytes, DsbkCrypto.MAX_IN_MEMORY_BYTES, heapCap)
        val exceeded = when {
            plainCap == DsbkCrypto.MAX_IN_MEMORY_BYTES -> DsbkError.V1_CONVERSION_REQUIRED
            maxOutputBytes <= plainCap -> DsbkError.OUTPUT_LIMIT_EXCEEDED
            else -> DsbkError.MEMORY_BUDGET_EXCEEDED
        }
        val cap = plainCap.toInt() + DsbkFormat.GCM_TAG_LEN
        val ciphertext = DsbkIo.readAllBounded(input, cap, exceeded)
        if (ciphertext.size < DsbkFormat.GCM_TAG_LEN) throw DsbkException(DsbkError.AUTHENTICATION_FAILED)
        val key = deriveKey(password, header.salt, header.params)
        try {
            val plaintext = AesGcm.open(
                key,
                header.nonce,
                ciphertext,
            ) ?: throw DsbkException(DsbkError.AUTHENTICATION_FAILED)
            try {
                if (plaintext.size.toLong() > maxOutputBytes) {
                    throw DsbkException(DsbkError.OUTPUT_LIMIT_EXCEEDED)
                }
                writeSink(sink, plaintext)
            } finally {
                plaintext.wipe()
            }
        } finally {
            key.wipe()
        }
    }

    private fun decryptV2(
        input: InputStream,
        header: DsbkV2Header,
        password: CharArray,
        sink: DsbkSink,
        maxOutputBytes: Long,
    ) {
        val cipherChunk = header.chunkSize + DsbkFormat.GCM_TAG_LEN
        val buffer = ByteArray(minOf(cipherChunk.toLong(), maxOutputBytes.coerceAtMost(Int.MAX_VALUE.toLong()) + DsbkFormat.GCM_TAG_LEN).toInt())
        var key: ByteArray? = null
        try {
            var first = -1
            var total = 0L
            var counter = 0L
            while (true) {
                val remaining = maxOutputBytes - total
                val readLimit = minOf(buffer.size.toLong(), remaining.coerceAtMost(Int.MAX_VALUE.toLong()) + DsbkFormat.GCM_TAG_LEN).toInt()
                val size = DsbkIo.fill(input, buffer, first, readLimit)
                val lookahead = input.read()
                validateCipherChunk(size, lookahead, cipherChunk)
                if (key == null) key = deriveKey(password, header.salt, header.params)
                val final = lookahead == -1
                total += decryptChunk(
                    key,
                    DsbkHeaderCodec.chunkNonce(header.prefix, counter, final),
                    buffer,
                    size,
                    sink,
                    maxOutputBytes - total,
                )
                if (final) break
                if (counter == 0xffffffffL) throw DsbkException(DsbkError.CHUNK_INVALID)
                first = lookahead
                counter++
            }
        } finally {
            buffer.wipe()
            key?.wipe()
        }
    }

    private fun validateCipherChunk(size: Int, lookahead: Int, cipherChunk: Int) {
        if (size == 0) throw DsbkException(DsbkError.DATA_MISSING)
        if (lookahead >= 0 && size < cipherChunk) throw DsbkException(DsbkError.OUTPUT_LIMIT_EXCEEDED)
        if (size < DsbkFormat.GCM_TAG_LEN) throw DsbkException(DsbkError.AUTHENTICATION_FAILED)
    }

    private fun decryptChunk(
        key: ByteArray,
        nonce: ByteArray,
        bytes: ByteArray,
        length: Int,
        sink: DsbkSink,
        remaining: Long,
    ): Long {
        val plaintext = AesGcm.open(
            key,
            nonce,
            bytes,
            0,
            length,
        ) ?: throw DsbkException(DsbkError.AUTHENTICATION_FAILED)
        return try {
            if (plaintext.size.toLong() > remaining) {
                throw DsbkException(DsbkError.OUTPUT_LIMIT_EXCEEDED)
            }
            writeSink(sink, plaintext)
            plaintext.size.toLong()
        } finally {
            plaintext.wipe()
        }
    }

    internal fun authenticateFirstChunk(prefix: ByteArray, header: DsbkV2Header, size: Long, password: CharArray) =
        synchronized(DsbkMemoryBudget.operationGate) {
            DsbkMemoryBudget.requireAvailable(header.params, reserveBytes = prefix.size.toLong() * DsbkMemoryBudget.V2_ARRAY_FACTOR)
            val key = deriveKey(password, header.salt, header.params)
            val offset = DsbkFormat.V2_HEADER_LEN
            try {
                val plaintext = AesGcm.open(key, DsbkHeaderCodec.chunkNonce(header.prefix, 0, prefix.size.toLong() == size),
                    prefix, offset, prefix.size - offset) ?: throw DsbkException(DsbkError.AUTHENTICATION_FAILED)
                plaintext.wipe()
            } finally { key.wipe() }
        }

    private fun validateParams(params: DsbkKdfParams) {
        params.validateBudget()
        if (params.memoryKiB > maxMemoryKiB) throw DsbkException(DsbkError.KDF_BUDGET_EXCEEDED)
        params.validateForDerivation()
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, params: DsbkKdfParams): ByteArray {
        validateParams(params)
        return try {
            deriver.derive(password, salt, params).also { key ->
                if (key.size != DsbkFormat.KEY_LEN) {
                    key.wipe()
                    throw DsbkException(DsbkError.KDF_FAILED)
                }
            }
        } catch (error: DsbkException) {
            throw error
        } catch (_: Exception) {
            throw DsbkException(DsbkError.KDF_FAILED)
        }
    }

    private fun writeSink(sink: DsbkSink, bytes: ByteArray) {
        try {
            sink.write(bytes, 0, bytes.size)
        } catch (error: DsbkException) {
            throw error
        } catch (_: Exception) {
            throw DsbkException(DsbkError.STAGING_FAILED)
        }
    }

    private fun DsbkSink.abortQuietly() {
        runCatching { abort() }
    }
}
