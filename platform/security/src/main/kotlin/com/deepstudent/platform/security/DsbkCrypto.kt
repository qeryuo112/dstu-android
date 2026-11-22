package com.deepstudent.platform.security

import java.io.InputStream
import java.io.OutputStream

/**
 * Production DSBK password entry point.
 *
 * New containers always use [PRODUCTION_KDF]. Cheap Argon2 parameters are
 * accepted only when reading a container header; test vectors use that read
 * path and never become the production write default.
 */
class DsbkCrypto internal constructor(
    private val engine: DsbkEngine,
    private val audit: SecurityAudit,
) {
    fun encryptV1(plaintext: ByteArray, password: CharArray): ByteArray {
        audit.record(SecurityOperation.DSBK_ENCRYPT, SecurityOutcome.STARTED, bytes = plaintext.size.toLong())
        return try {
            val encrypted = engine.encryptV1(
                plaintext,
                password,
                engine.newSalt(),
                PRODUCTION_KDF,
                engine.newNonce(),
            )
            audit.record(SecurityOperation.DSBK_ENCRYPT, SecurityOutcome.SUCCEEDED, bytes = encrypted.size.toLong())
            encrypted
        } catch (error: DsbkException) {
            audit.record(
                SecurityOperation.DSBK_ENCRYPT,
                SecurityOutcome.FAILED,
                errorCode = error.error.name,
            )
            throw error
        }
    }

    fun encryptV2(
        input: InputStream,
        output: OutputStream,
        password: CharArray,
        maxInputBytes: Long = DEFAULT_MAX_INPUT_BYTES,
    ) {
        audit.record(SecurityOperation.DSBK_ENCRYPT, SecurityOutcome.STARTED)
        try {
            engine.encryptV2(
                input,
                output,
                password,
                engine.newSalt(),
                PRODUCTION_KDF,
                engine.newPrefix(),
                maxInputBytes,
            )
            audit.record(SecurityOperation.DSBK_ENCRYPT, SecurityOutcome.SUCCEEDED)
        } catch (error: DsbkException) {
            audit.record(
                SecurityOperation.DSBK_ENCRYPT,
                SecurityOutcome.FAILED,
                errorCode = error.error.name,
            )
            throw error
        }
    }

    /**
     * Consumes only the 44/45-byte header; reopen/rewind before decrypting.
     * `containerBytes`, when known, must be the exact length of this same
     * stream. Unknown lengths are bounded again during decrypt. This result is
     * unauthenticated metadata, not proof of a correct password or content.
     */
    fun preflight(
        input: InputStream,
        containerBytes: Long? = null,
        maxOutputBytes: Long = DEFAULT_MAX_INPUT_BYTES,
        maxInputBytes: Long = DEFAULT_MAX_CONTAINER_BYTES,
    ): DsbkPreflight {
        val trace = SecurityTrace(audit, SecurityOperation.DSBK_PREFLIGHT)
        trace.start(SecurityPhase.HEADER)
        return try {
            DsbkPreflightReader.read(
                input, containerBytes, maxOutputBytes, maxInputBytes,
                PRODUCTION_MAX_MEMORY_KIB, PRODUCTION_MAX_CHUNK_BYTES,
            ).info.also { trace.succeed() }
        } catch (error: DsbkException) {
            trace.fail(error)
            throw error
        } catch (_: Exception) {
            val error = DsbkException(DsbkError.IO_FAILED)
            trace.fail(error)
            throw error
        }
    }

    fun decrypt(
        input: InputStream,
        password: CharArray,
        sink: DsbkSink,
        maxOutputBytes: Long,
        maxInputBytes: Long = DEFAULT_MAX_CONTAINER_BYTES,
    ) {
        val trace = SecurityTrace(audit, SecurityOperation.DSBK_DECRYPT)
        trace.start(SecurityPhase.DISPATCH)
        try {
            engine.decrypt(input, password, sink, maxOutputBytes, maxInputBytes, trace)
            trace.succeed()
        } catch (error: DsbkException) {
            trace.fail(error)
            throw error
        }
    }

    fun decryptToBytes(
        input: InputStream,
        password: CharArray,
        maxOutputBytes: Long,
    ): ByteArray {
        if (maxOutputBytes < 0) throw DsbkException(DsbkError.OUTPUT_LIMIT_EXCEEDED)
        val sink = ByteArrayDsbkSink(minOf(maxOutputBytes, MAX_IN_MEMORY_BYTES))
        decrypt(input, password, sink, minOf(maxOutputBytes, MAX_IN_MEMORY_BYTES))
        return sink.bytes()
    }

    /** Number of leading bytes needed to authenticate a repository backup: all of v1, first chunk of v2. */
    fun passwordProofBytes(header: ByteArray, containerBytes: Long): Int = passwordProofLength(proofHeader(header, containerBytes))

    fun provePassword(prefix: ByteArray, containerBytes: Long, password: CharArray) {
        val parsed = proofHeader(prefix, containerBytes)
        if (prefix.size != passwordProofLength(parsed)) throw DsbkException(DsbkError.HEADER_INVALID)
        val header = parsed.v2
        if (header != null) {
            engine.authenticateFirstChunk(prefix, header, containerBytes, password)
        } else {
            decrypt(prefix.inputStream(), password, DiscardProofPlaintext(), MAX_IN_MEMORY_BYTES, containerBytes)
        }
    }

    fun isEncryptedBackup(bytes: ByteArray): Boolean = DsbkFormat.isEncryptedBackup(bytes)

    companion object {
        val PRODUCTION_KDF: DsbkKdfParams = DsbkKdfParams.PRODUCTION
        const val DEFAULT_MAX_INPUT_BYTES = 1024L * 1024L * 1024L
        const val DEFAULT_MAX_CONTAINER_BYTES = DEFAULT_MAX_INPUT_BYTES + 16445L
        const val MAX_IN_MEMORY_BYTES = 64L * 1024L * 1024L
        // Read compatibility follows the frozen desktop bounds, not the write
        // defaults. The shared KDF gate prevents concurrent large derivations.
        const val PRODUCTION_MAX_MEMORY_KIB = DsbkFormat.MAX_MEMORY_KIB
        const val PRODUCTION_MAX_CHUNK_BYTES = DsbkFormat.MAX_STREAM_CHUNK

        fun production(audit: SecurityAudit = SecurityAudit.NONE): DsbkCrypto =
            DsbkCrypto(
                DsbkEngine(
                    Argon2idKeyDeriver(),
                    maxMemoryKiB = PRODUCTION_MAX_MEMORY_KIB,
                    maxChunkBytes = PRODUCTION_MAX_CHUNK_BYTES,
                ),
                audit,
            )
    }
}
