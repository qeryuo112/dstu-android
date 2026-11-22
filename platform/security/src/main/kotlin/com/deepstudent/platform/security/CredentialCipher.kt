package com.deepstudent.platform.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The versioned credential wire record is separate from DSBK backup containers. */
internal class CredentialCipher(private val random: SecureRandom) {
    fun encrypt(key: CredentialKey, plaintext: ByteArray, master: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        return try {
            val cipher = createCipher(Cipher.ENCRYPT_MODE, master, nonce, key)
            MAGIC + byteArrayOf(VERSION) + nonce + cipher.doFinal(plaintext)
        } catch (_: GeneralSecurityException) {
            throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
        }
    }

    fun decrypt(key: CredentialKey, record: ByteArray, master: ByteArray): ByteArray {
        if (record.size < HEADER_BYTES + NONCE_BYTES + TAG_BYTES ||
            !record.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) || record[4] != VERSION
        ) throw CredentialStoreException(CredentialStoreError.INTEGRITY_FAILURE)
        val nonce = record.copyOfRange(HEADER_BYTES, HEADER_BYTES + NONCE_BYTES)
        return try {
            val cipher = createCipher(Cipher.DECRYPT_MODE, master, nonce, key)
            val offset = HEADER_BYTES + NONCE_BYTES
            cipher.doFinal(record, offset, record.size - offset).also { plaintext ->
                if (plaintext.size > SecretBytes.MAX_SIZE_BYTES) {
                    plaintext.wipe()
                    throw CredentialStoreException(CredentialStoreError.INTEGRITY_FAILURE)
                }
            }
        } catch (_: GeneralSecurityException) {
            throw CredentialStoreException(CredentialStoreError.INTEGRITY_FAILURE)
        }
    }

    private fun createCipher(mode: Int, master: ByteArray, nonce: ByteArray, key: CredentialKey): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(master, "AES"), GCMParameterSpec(128, nonce))
            updateAAD((AAD_DOMAIN + key.value).toByteArray(Charsets.UTF_8))
        }

    companion object {
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val HEADER_BYTES = 5
        private const val VERSION: Byte = 1
        private const val AAD_DOMAIN = "deepstudent.credential.v1\u0000"
        private val MAGIC = byteArrayOf(0x44, 0x53, 0x43, 0x31)
    }
}
