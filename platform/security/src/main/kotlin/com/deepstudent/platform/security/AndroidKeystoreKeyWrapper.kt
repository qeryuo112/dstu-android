package com.deepstudent.platform.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore-backed AES-GCM wrapper. The wrapping key never leaves the
 * Keystore; only a versioned IV and ciphertext are persisted by
 * [WrappedMasterKeyStore].
 */
class AndroidKeystoreKeyWrapper(
    private val alias: String = DEFAULT_ALIAS,
) : KeyWrapper {
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }
    private val keyLock = ALIAS_LOCKS.computeIfAbsent(alias) { Any() }

    override fun wrap(plaintext: ByteArray): ByteArray = synchronized(keyLock) {
        validatePlaintext(plaintext)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, ensureKey())
            val iv = cipher.iv
            if (iv.size !in MIN_IV_BYTES..MAX_IV_BYTES) {
                throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
            }
            val ciphertext = cipher.doFinal(plaintext)
            ByteArray(2 + iv.size + ciphertext.size).also { payload ->
                payload[0] = FORMAT_VERSION
                payload[1] = iv.size.toByte()
                iv.copyInto(payload, 2)
                ciphertext.copyInto(payload, 2 + iv.size)
            }
        } catch (error: CredentialStoreException) {
            throw error
        } catch (_: Exception) {
            throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
        }
    }

    override fun unwrap(wrapped: ByteArray): ByteArray = synchronized(keyLock) {
        val ivLength = validateWrapped(wrapped)
        val iv = wrapped.copyOfRange(2, 2 + ivLength)
        val ciphertext = wrapped.copyOfRange(2 + ivLength, wrapped.size)
        try {
            // Never create a replacement alias on an unwrap path. A confirmed
            // missing alias is different from an unavailable Keystore service.
            if (!keyStore.containsAlias(alias)) throw masterKeyLost()
            val key = keyStore.getKey(alias, null) as? SecretKey
                ?: throw masterKeyUnconfirmed()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (error: CredentialStoreException) {
            throw error
        } catch (_: AEADBadTagException) {
            throw masterKeyLost()
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw masterKeyLost()
        } catch (_: Exception) {
            // Never treat an unspecified provider/service failure as permission
            // to delete a user's credentials.
            throw masterKeyUnconfirmed()
        }
    }

    private fun validateWrapped(wrapped: ByteArray): Int {
        if (wrapped.size < 2 + MIN_IV_BYTES + TAG_BYTES || wrapped[0] != FORMAT_VERSION) {
            throw masterKeyUnconfirmed()
        }
        val ivLength = wrapped[1].toInt() and 0xff
        if (ivLength !in MIN_IV_BYTES..MAX_IV_BYTES || wrapped.size != 2 + ivLength + TAG_BYTES + WrappedMasterKeyStore.MASTER_KEY_BYTES) {
            throw masterKeyUnconfirmed()
        }
        return ivLength
    }

    override fun deleteKey() {
        synchronized(keyLock) {
            try {
                keyStore.deleteEntry(alias)
            } catch (_: Exception) {
                throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
            }
        }
    }

    private fun ensureKey(): SecretKey = synchronized(keyLock) {
        val existing = keyStore.getKey(alias, null)
        if (existing is SecretKey) return existing
        if (keyStore.containsAlias(alias)) {
            throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
        }
        val generator = KeyGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    }

    private fun validatePlaintext(plaintext: ByteArray) {
        if (plaintext.size != WrappedMasterKeyStore.MASTER_KEY_BYTES) {
            throw CredentialStoreException(CredentialStoreError.INVALID_VALUE)
        }
    }

    companion object {
        private val ALIAS_LOCKS = ConcurrentHashMap<String, Any>()
        const val DEFAULT_ALIAS = "com.deepstudent.platform.security.master.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val TAG_BYTES = 16
        private const val MIN_IV_BYTES = 12
        private const val MAX_IV_BYTES = 16
        private const val FORMAT_VERSION: Byte = 1
    }
}
