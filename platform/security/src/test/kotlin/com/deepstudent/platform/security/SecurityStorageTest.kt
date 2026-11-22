package com.deepstudent.platform.security

import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecurityStorageTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun wrappedMasterKeySurvivesReopenAndTamperFailsClosed() {
        val wrapper = TestKeyWrapper()
        val file = File(temporary.root, "master.key")
        val first = WrappedMasterKeyStore(file, wrapper).loadOrCreate()
        val firstBytes = first.use { it.copyOf() }
        first.close()
        val second = WrappedMasterKeyStore(file, wrapper).loadOrCreate()
        val secondBytes = second.use { it.copyOf() }
        second.close()
        assertArrayEquals(firstBytes, secondBytes)
        assertTrue(wrapper.wrapCount >= 1)

        flipLast(file)
        val failure = assertThrows(CredentialStoreException::class.java) {
            WrappedMasterKeyStore(file, wrapper).loadOrCreate()
        }
        assertEquals(CredentialStoreError.MASTER_KEY_UNAVAILABLE, failure.error)
        WrappedMasterKeyStore(file, wrapper).delete()
        assertFalse(file.exists())
        assertTrue(wrapper.deleted)
    }

    @Test
    fun credentialRecordsAreAuthenticatedAndContainNoPlaintext() {
        val root = temporary.root
        val wrapper = TestKeyWrapper()
        val master = WrappedMasterKeyStore(File(root, "master.key"), wrapper)
        val events = mutableListOf<SecurityAuditEvent>()
        val store = EncryptedCredentialStore(
            File(root, "credentials"),
            master,
            SecurityAudit { events += it },
        )
        val key = CredentialKey.of("provider.api")
        val secret = "s3cr3t-凭据-🔐-sentinel".toCharArray()
        val secretBytes = String(secret).toByteArray(Charsets.UTF_8)
        val value = SecretBytes.fromUtf8(secret)
        store.put(key, value)
        value.close()

        val loaded = store.get(key)
        loaded.use { assertArrayEquals(secretBytes, it) }
        loaded.close()
        store.close()

        val files = root.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.isNotEmpty())
        val secretText = String(secretBytes, Charsets.UTF_8)
        for (file in files) {
            assertFalse(file.name.contains(secretText))
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(secretText))
        }
        assertEquals(0, events.count { it.toString().contains(secretText) })
        assertTrue(events.any { it.operation == SecurityOperation.CREDENTIAL_PUT && it.outcome == SecurityOutcome.SUCCEEDED })

        val record = files.first { it.name.endsWith(".dsc") }
        assertTrue(record.nameWithoutExtension.matches(Regex("[0-9a-f]{64}")))
        flipLast(record)
        val reopened = EncryptedCredentialStore(File(root, "credentials"), master)
        val failure = assertThrows(CredentialStoreException::class.java) { reopened.get(key) }
        assertEquals(CredentialStoreError.INTEGRITY_FAILURE, failure.error)
        reopened.close()
    }

    @Test
    fun lostWrappingKeyDoesNotReplaceTheWrappedMaster() {
        val wrapper = TestKeyWrapper()
        val file = File(temporary.root, "master.key")
        val master = WrappedMasterKeyStore(file, wrapper).loadOrCreate()
        master.close()
        val before = file.readBytes()
        wrapper.deleteKey()

        val failure = assertThrows(CredentialStoreException::class.java) {
            WrappedMasterKeyStore(file, wrapper).loadOrCreate()
        }
        assertEquals(CredentialStoreError.MASTER_KEY_UNAVAILABLE, failure.error)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun closedSecretCanBePrintedWithoutThrowingOrLeaking() {
        val secret = SecretBytes.fromUtf8("private-value".toCharArray())
        secret.close()
        val text = secret.toString()
        assertTrue(text.contains("redacted"))
        assertTrue(text.contains("closed=true"))
        assertFalse(text.contains("private-value"))
    }

    @Test
    fun deletingCredentialIsIdempotentAndCloseWipesMasterCache() {
        val wrapper = TestKeyWrapper()
        val master = WrappedMasterKeyStore(File(temporary.root, "master.key"), wrapper)
        val store = EncryptedCredentialStore(File(temporary.root, "credentials"), master)
        val key = CredentialKey.of("delete-me")
        val value = SecretBytes.fromUtf8("value".toCharArray())
        store.put(key, value)
        value.close()
        store.delete(key)
        store.delete(key)
        val failure = assertThrows(CredentialStoreException::class.java) { store.get(key) }
        assertEquals(CredentialStoreError.NOT_FOUND, failure.error)
        store.close()
        val closedFailure = assertThrows(CredentialStoreException::class.java) {
            store.get(key)
        }
        assertEquals(CredentialStoreError.CLOSED, closedFailure.error)
    }

    @Test
    fun secretBufferWipesAndDiagnosticsRedactKnownForms() {
        val raw = "wiping-secret".toByteArray()
        val secret = SecretBytes.takeOwnership(raw)
        assertEquals(raw.size, secret.size)
        secret.close()
        assertTrue(raw.all { it == 0.toByte() })

        val redacted = SecurityDiagnostics.redact(
            "password=hunter2 token=abc123 url=https://user:pass@example.test/x",
        )
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("user:pass"))
        assertTrue(redacted.contains(SecurityDiagnostics.REDACTED))
        assertEquals(
            "credential data failed authentication",
            SecurityDiagnostics.forCredential(CredentialStoreError.INTEGRITY_FAILURE).message,
        )
    }

    @Test
    fun utf8PasswordBytesMatchDesktopSurrogateReplacement() {
        val encoded = Utf8Bytes.encode(charArrayOf('a', '\uD800', 'b'))
        assertArrayEquals(
            byteArrayOf(0x61, 0xef.toByte(), 0xbf.toByte(), 0xbd.toByte(), 0x62),
            encoded,
        )
        val secret = SecretBytes.fromUtf8(charArrayOf('\uD800'))
        secret.use {
            assertArrayEquals(byteArrayOf(0xef.toByte(), 0xbf.toByte(), 0xbd.toByte()), it)
        }
        secret.close()
    }

    @Test
    fun diagnosticsRedactJsonAndPemForms() {
        val redacted = SecurityDiagnostics.redact(
            """{"password":"hunter2","client_secret":"s3cr3t","Authorization":"Bearer tok","private_key":"MIIB"}""",
        )
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("s3cr3t"))
        assertFalse(redacted.contains("Bearer tok"))
        assertFalse(redacted.contains("MIIB"))
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIBsecret\n-----END PRIVATE KEY-----"
        assertFalse(SecurityDiagnostics.redact(pem).contains("MIIBsecret"))
    }

    @Test
    fun credentialRecordIsBoundToItsLogicalKey() {
        val root = temporary.root
        val master = WrappedMasterKeyStore(File(root, "master.key"), TestKeyWrapper())
        val directory = File(root, "credentials")
        val store = EncryptedCredentialStore(directory, master)
        try {
            val alpha = SecretBytes.fromUtf8("alpha-value".toCharArray())
            val beta = SecretBytes.fromUtf8("beta-value".toCharArray())
            store.put(CredentialKey.of("alpha"), alpha)
            store.put(CredentialKey.of("beta"), beta)
            alpha.close()
            beta.close()

            val alphaRecord = File(directory, digestFull("deepstudent.credential.file.v1\u0000alpha") + ".dsc")
            val betaRecord = File(directory, digestFull("deepstudent.credential.file.v1\u0000beta") + ".dsc")
            assertTrue(alphaRecord.isFile)
            assertTrue(betaRecord.isFile)
            alphaRecord.copyTo(betaRecord, overwrite = true)

            val failure = assertThrows(CredentialStoreException::class.java) {
                store.get(CredentialKey.of("beta"))
            }
            assertEquals(CredentialStoreError.INTEGRITY_FAILURE, failure.error)
        } finally {
            store.close()
        }
    }

    @Test
    fun credentialKeyValidationAndAuditFormattingExposeNoValue() {
        assertThrows(IllegalArgumentException::class.java) { CredentialKey.of("bad key with spaces") }
        val event = SecurityAuditEvent(
            SecurityOperation.CREDENTIAL_GET,
            SecurityOutcome.SUCCEEDED,
            resourceDigest = "0123456789ab",
            bytes = 4,
        )
        assertFalse(event.toString().contains("password"))
        assertEquals("platform.security", event.owner)
        assertNotEquals("CredentialKey(redacted)", CredentialKey.of("provider").value)
    }

    private fun flipLast(file: File) {
        RandomAccessFile(file, "rw").use { access ->
            val last = access.length() - 1
            access.seek(last)
            val old = access.read()
            access.seek(last)
            access.write(old xor 1)
        }
    }
}

private class TestKeyWrapper : KeyWrapper {
    private val wrappingKey = ByteArray(32) { (it * 7 + 3).toByte() }
    var wrapCount = 0
        private set
    var deleted = false
        private set

    override fun wrap(plaintext: ByteArray): ByteArray {
        wrapCount++
        val nonce = ByteArray(12) { (it * 11 + 5).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(wrappingKey, "AES"),
            GCMParameterSpec(128, nonce),
        )
        val ciphertext = cipher.doFinal(plaintext)
        return byteArrayOf(1, nonce.size.toByte()) + nonce + ciphertext
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (wrapped.size < 2 + 12 + 16 || wrapped[0].toInt() != 1) {
            throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
        }
        val nonceLength = wrapped[1].toInt()
        val nonce = wrapped.copyOfRange(2, 2 + nonceLength)
        val ciphertext = wrapped.copyOfRange(2 + nonceLength, wrapped.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(wrappingKey, "AES"),
            GCMParameterSpec(128, nonce),
        )
        return cipher.doFinal(ciphertext)
    }

    override fun deleteKey() {
        deleted = true
        wrappingKey.wipe()
    }
}
