package com.deepstudent.platform.security

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object AesGcm {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        offset: Int = 0,
        length: Int = plaintext.size,
    ): ByteArray {
        requireRange(plaintext, offset, length)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        return cipher.doFinal(plaintext, offset, length)
    }

    fun open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        offset: Int = 0,
        length: Int = ciphertext.size,
    ): ByteArray? {
        requireRange(ciphertext, offset, length)
        if (length < DsbkFormat.GCM_TAG_LEN) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.doFinal(ciphertext, offset, length)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun requireRange(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "cipher range is invalid"
        }
    }
}
