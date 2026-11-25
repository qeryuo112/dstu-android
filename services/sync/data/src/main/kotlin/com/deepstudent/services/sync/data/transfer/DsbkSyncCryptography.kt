package com.deepstudent.services.sync.data.transfer

import com.deepstudent.core.common.id.Digests
import com.deepstudent.platform.security.Argon2idKeyDeriver
import com.deepstudent.platform.security.DsbkCrypto
import com.deepstudent.platform.security.DsbkError
import com.deepstudent.platform.security.DsbkException
import com.deepstudent.platform.security.DsbkFormat
import com.deepstudent.platform.security.DsbkKdfParams
import com.deepstudent.platform.security.DsbkSink
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.SyncCryptography
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.SyncObjectLimits
import com.deepstudent.services.sync.backend.SyncPasswordVerifier
import com.deepstudent.services.sync.backend.SyncPayloadCodec
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

class DsbkSyncCryptography(private val crypto: DsbkCrypto) : SyncCryptography {
    private val random = SecureRandom()
    private val deriver = Argon2idKeyDeriver()

    override fun createVerifier(password: CharArray): SyncPasswordVerifier = dsbk(SyncError.INVALID_ENCRYPTION_MARKER) {
        val salt = ByteArray(DsbkFormat.SALT_LEN).also(random::nextBytes)
        val params = DsbkCrypto.PRODUCTION_KDF
        val digest = verifierDigest(password, salt, params)
        try {
            SyncPasswordVerifier(params.memoryKiB, params.iterations, params.parallelism, Digests.toHex(salt), Digests.toHex(digest))
        } finally {
            digest.fill(0)
        }
    }

    override fun verify(password: CharArray, verifier: SyncPasswordVerifier): Boolean = dsbk(SyncError.INVALID_ENCRYPTION_MARKER) {
        val params = DsbkKdfParams(verifier.memoryKiB, verifier.iterations, verifier.parallelism)
        val actual = verifierDigest(password, unhex(verifier.saltHex), params)
        try {
            MessageDigest.isEqual(actual, unhex(verifier.digestHex))
        } finally {
            actual.fill(0)
        }
    }

    override fun payload(password: CharArray): SyncPayloadCodec = DsbkSyncPayload(crypto, password)

    override suspend fun proveBackupPassword(provider: ObjectProvider, key: String, size: Long, checksum: String, password: CharArray) {
        val headerBytes = minOf(size, DsbkFormat.V1_HEADER_LEN.toLong()).toInt()
        if (headerBytes < DsbkFormat.V2_HEADER_LEN) throw SyncFailure(SyncError.ENCRYPTED_DATA_INVALID)
        val header = provider.getPrefix(key, headerBytes, size) ?: throw SyncFailure(SyncError.NOT_FOUND)
        if (!crypto.isEncryptedBackup(header)) throw SyncFailure(SyncError.ENCRYPTION_MODE_MISMATCH)
        val length = dsbk(SyncError.ENCRYPTED_DATA_INVALID) { crypto.passwordProofBytes(header, size) }
        val bytes = provider.getPrefix(key, length, size) ?: throw SyncFailure(SyncError.NOT_FOUND)
        verifyProofRead(bytes, header, length, size, checksum)
        authenticateBackup(bytes, size, password)
    }

    private fun verifyProofRead(bytes: ByteArray, header: ByteArray, length: Int, size: Long, checksum: String) {
        if (bytes.size != length || !bytes.startsWithHeader(header) ||
            (size == length.toLong() && !Digests.sha256Hex(bytes).equals(checksum, ignoreCase = true))
        ) {
            throw SyncFailure(SyncError.ENCRYPTED_DATA_INVALID)
        }
    }

    private fun authenticateBackup(bytes: ByteArray, size: Long, password: CharArray) {
        try {
            crypto.provePassword(bytes, size, password)
        } catch (error: DsbkException) {
            if (error.error == DsbkError.AUTHENTICATION_FAILED) throw SyncFailure(SyncError.ENCRYPTION_PASSWORD_INCORRECT)
            dsbk(SyncError.ENCRYPTED_DATA_INVALID) { throw error }
        }
    }

    private fun ByteArray.startsWithHeader(header: ByteArray): Boolean =
        size >= header.size && header.indices.all { this[it] == header[it] }

    private fun verifierDigest(password: CharArray, salt: ByteArray, params: DsbkKdfParams): ByteArray {
        val key = deriver.derive(password, salt, params)
        return try {
            MessageDigest.getInstance("SHA-256").apply { update(VERIFIER_DOMAIN) }.digest(key)
        } finally {
            key.fill(0)
        }
    }

    private companion object {
        val VERIFIER_DOMAIN = "deep-student.encryption-marker.verifier.v1".toByteArray()
    }
}

private class DsbkSyncPayload(crypto: DsbkCrypto, password: CharArray) : SyncPayloadCodec {
    private val session = crypto.openSession(password)

    override fun close() = session.close()
    override val encrypted = true

    override fun encode(bytes: ByteArray, blob: Boolean): ByteArray = dsbk(SyncError.STORAGE) {
        SyncObjectLimits.requirePayload(bytes)
        if (!blob) {
            session.encryptV1(bytes)
        } else {
            ByteArrayOutputStream(bytes.size + 300).also { output -> encodeBlob(bytes.inputStream(), output) }.toByteArray()
        }
    }

    override fun decode(bytes: ByteArray, blob: Boolean): ByteArray = dsbk(SyncError.ENCRYPTED_DATA_INVALID) {
        if (bytes.size > SyncObjectLimits.WIRE_BYTES) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
        if (!DsbkFormat.hasMagic(bytes, 0)) throw SyncFailure(SyncError.ENCRYPTION_MODE_MISMATCH)
        session.decryptToBytes(bytes.inputStream(), SyncObjectLimits.PAYLOAD_BYTES.toLong())
    }

    override fun encodeBlob(input: InputStream, output: OutputStream) = dsbk(SyncError.STORAGE) {
        session.encryptV2(input, output, SyncObjectLimits.PAYLOAD_BYTES.toLong())
    }

    override fun decodeBlob(input: InputStream, output: OutputStream) = dsbk(SyncError.ENCRYPTED_DATA_INVALID) {
        session.decrypt(
            input,
            object : DsbkSink {
                override var stagedBytes = 0L
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    output.write(bytes, offset, length)
                    stagedBytes += length.toLong()
                }
                override fun commit() = output.flush()
                override fun abort() = Unit // The owner deletes the entire unpublished stage.
            },
            SyncObjectLimits.PAYLOAD_BYTES.toLong(),
        )
    }
}

private fun unhex(value: String): ByteArray = ByteArray(value.length / 2) { offset ->
    value.substring(offset * 2, offset * 2 + 2).toInt(16).toByte()
}

private inline fun <T> dsbk(default: SyncError, block: () -> T): T = try {
    block()
} catch (error: DsbkException) {
    throw SyncFailure(
        when (error.error) {
            DsbkError.KDF_BUDGET_EXCEEDED, DsbkError.INPUT_LIMIT_EXCEEDED, DsbkError.OUTPUT_LIMIT_EXCEEDED,
            DsbkError.MEMORY_BUDGET_EXCEEDED, DsbkError.V1_CONVERSION_REQUIRED,
            -> SyncError.LIMIT_EXCEEDED

            else -> default
        },
    )
}
