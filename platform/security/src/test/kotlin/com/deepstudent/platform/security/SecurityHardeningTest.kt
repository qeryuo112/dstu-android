package com.deepstudent.platform.security

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecurityHardeningTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun concurrentMasterCreationUsesOneWrappedKey() {
        val wrapper = CountingKeyWrapper()
        val file = File(temporary.root, "master.key")
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                pool.submit<ByteArray> {
                    barrier.await(10, TimeUnit.SECONDS)
                    WrappedMasterKeyStore(file, wrapper).loadOrCreate().use { secret -> secret.copyOf() }
                }
            }
            val first = futures[0].get(30, TimeUnit.SECONDS)
            val second = futures[1].get(30, TimeUnit.SECONDS)
            assertArrayEquals(first, second)
            assertEquals(1, wrapper.wraps.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun masterKeySymlinkIsRejectedWithoutTouchingTarget() {
        val victim = File(temporary.root, "victim.bin").apply {
            writeBytes("victim-content".toByteArray())
        }
        val link = File(temporary.root, "master.key")
        Files.createSymbolicLink(link.toPath(), victim.toPath())

        val failure = assertThrows(CredentialStoreException::class.java) {
            WrappedMasterKeyStore(link, CountingKeyWrapper()).loadOrCreate()
        }
        assertEquals(CredentialStoreError.MASTER_KEY_UNAVAILABLE, failure.error)
        assertArrayEquals("victim-content".toByteArray(), victim.readBytes())
    }

    @Test
    fun credentialRecordSymlinkIsNotFollowed() {
        val wrapper = CountingKeyWrapper()
        val master = WrappedMasterKeyStore(File(temporary.root, "master.key"), wrapper)
        val directory = File(temporary.root, "credentials").apply { check(mkdirs()) }
        val victim = File(temporary.root, "victim.bin").apply {
            writeBytes("do-not-overwrite".toByteArray())
        }
        val key = CredentialKey.of("provider.api")
        val record = File(
            directory,
            digestFull("deepstudent.credential.file.v1\u0000" + key.value) + ".dsc",
        )
        Files.createSymbolicLink(record.toPath(), victim.toPath())
        val store = EncryptedCredentialStore(directory, master)
        try {
            val value = SecretBytes.fromUtf8("secret".toCharArray())
            val failure = assertThrows(CredentialStoreException::class.java) { store.put(key, value) }
            value.close()
            assertEquals(CredentialStoreError.STORAGE_UNAVAILABLE, failure.error)
            assertArrayEquals("do-not-overwrite".toByteArray(), victim.readBytes())
        } finally {
            store.close()
        }
    }

    @Test
    fun deletingWrappedMasterInvalidatesAnAlreadyCachedStore() {
        val wrapper = CountingKeyWrapper()
        val file = File(temporary.root, "master.key")
        val store = EncryptedCredentialStore(
            File(temporary.root, "credentials"),
            WrappedMasterKeyStore(file, wrapper),
        )
        val key = CredentialKey.of("cached.credential")
        val value = SecretBytes.fromUtf8("cached-value".toCharArray())
        try {
            store.put(key, value)
            value.close()
            assertTrue(SecureFiles.deleteBestEffort(file))
            val failure = assertThrows(CredentialStoreException::class.java) { store.get(key) }
            assertEquals(CredentialStoreError.MASTER_KEY_UNAVAILABLE, failure.error)
        } finally {
            value.close()
            store.close()
        }
    }

    @Test
    fun rebuildingMasterKeyDropsOldCredentialsAndCreatesUsableMaster() {
        val root = temporary.root
        val file = File(root, "master.key")
        val store = EncryptedCredentialStore(
            File(root, "credentials"),
            WrappedMasterKeyStore(file, CountingKeyWrapper()),
        )
        val oldKey = CredentialKey.of("old.credential")
        val oldValue = SecretBytes.fromUtf8("old-value".toCharArray())
        try {
            store.put(oldKey, oldValue)
            oldValue.close()
            val oldRecord = File(
                File(root, "credentials"),
                digestFull("deepstudent.credential.file.v1\u0000" + oldKey.value) + ".dsc",
            )
            assertTrue(oldRecord.isFile)

            store.rebuildMasterKey()
            assertTrue(file.isFile)
            assertFalse(oldRecord.exists())
            val missing = assertThrows(CredentialStoreException::class.java) { store.get(oldKey) }
            assertEquals(CredentialStoreError.NOT_FOUND, missing.error)

            val newKey = CredentialKey.of("new.credential")
            val newValue = SecretBytes.fromUtf8("new-value".toCharArray())
            store.put(newKey, newValue)
            newValue.close()
            store.get(newKey).use { assertArrayEquals("new-value".toByteArray(), it) }
        } finally {
            oldValue.close()
            store.close()
        }
    }

    @Test
    fun abortedByteArraySinkWipesItsOwnedStorage() {
        val sink = ByteArrayDsbkSink(1024)
        val secret = "plaintext-sentinel".toByteArray()
        sink.write(secret, 0, secret.size)
        val retained = sink.storage
        sink.abort()
        assertEquals(0, sink.stagedBytes)
        assertTrue(retained.all { it == 0.toByte() })
        assertTrue(sink.storage.isEmpty())
        assertThrows(DsbkException::class.java) { sink.bytes() }
    }

    @Test
    fun factoryRecoversStagingAndWiresCredentialAndDsbkPorts() {
        val staging = File(temporary.root, "staging").apply { check(mkdirs()) }
        val orphan = File(staging, ".dstu-stale.part").apply { writeBytes(ByteArray(32) { 1 }) }
        val ports = SecurityFactory.create(temporary.root, CountingKeyWrapper())
        try {
            assertFalse(orphan.exists())
            val key = CredentialKey.of("factory.credential")
            val value = SecretBytes.fromUtf8("factory-secret".toCharArray())
            ports.credentials.put(key, value)
            value.close()
            ports.credentials.get(key).use { assertArrayEquals("factory-secret".toByteArray(), it) }
            val sink = ports.staging.createSink(File(temporary.root, "active.bin"))
            val failure = assertThrows(DsbkException::class.java) {
                ports.dsbk.decrypt(
                    java.io.ByteArrayInputStream(ByteArray(0)),
                    "pw".toCharArray(),
                    sink,
                    0,
                )
            }
            assertEquals(DsbkError.HEADER_INVALID, failure.error)
        } finally {
            ports.close()
        }
    }

    private class CountingKeyWrapper : KeyWrapper {
        val wraps = AtomicInteger()
        private var generation = 0
        private var key = ByteArray(32) { (it * 13 + 5).toByte() }

        override fun wrap(plaintext: ByteArray): ByteArray {
            wraps.incrementAndGet()
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val ciphertext = cipher.doFinal(plaintext)
            return byteArrayOf(1, nonce.size.toByte()) + nonce + ciphertext
        }

        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (wrapped.size < 30 || wrapped[0].toInt() != 1) {
                throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
            }
            val nonceLength = wrapped[1].toInt()
            val nonce = wrapped.copyOfRange(2, 2 + nonceLength)
            val ciphertext = wrapped.copyOfRange(2 + nonceLength, wrapped.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            return cipher.doFinal(ciphertext)
        }

        override fun deleteKey() {
            generation++
            key.wipe()
            key = ByteArray(32) { ((it * 13 + 5 + generation) and 0xff).toByte() }
        }
    }
}
