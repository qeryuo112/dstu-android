package com.deepstudent.platform.security

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

/** JVM transaction tests use a software wrapper, NOT evidence of AndroidKeystore success. */
class SecurityConcurrencyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun secondAreaAndFactoryRecoveryCannotRemoveAnotherLiveSink() {
        val directory = File(temporary.root, "staging")
        val first = DsbkStagingArea(directory, 16)
        val second = DsbkStagingArea(directory, 16)
        try {
            val target = File(temporary.root, "active.bin")
            val sink = first.createSink(target)
            sink.write(byteArrayOf(1, 2, 3))
            assertEquals(0, second.cleanupOrphans())
            assertThrows(DsbkException::class.java) { second.createSink(target) }
            second.close()
            sink.commit()
            assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun lockedUnregisteredPartSurvivesCleanupUntilOwnerReleasesIt() {
        val directory = File(temporary.root, "staging").apply { check(mkdirs()) }
        val part = File(directory, ".dstu-other-process.part").apply { writeText("live-provisional") }
        DsbkStagingArea(directory, 64).use { area ->
            FileChannel.open(part.toPath(), StandardOpenOption.WRITE).use { channel ->
                val held = channel.lock()
                try {
                    assertEquals(0, area.cleanupOrphans())
                    assertEquals("live-provisional", part.readText())
                } finally { held.release() }
            }
            assertEquals(1, area.cleanupOrphans())
            assertFalse(part.exists())
        }
    }

    @Test
    fun combinedStagingBudgetCannotBeMultipliedByConcurrentSinks() {
        DsbkStagingArea(File(temporary.root, "staging"), 8).use { area ->
            val a = area.createSink(File(temporary.root, "a"))
            val b = area.createSink(File(temporary.root, "b"))
            a.write(ByteArray(6))
            val error = assertThrows(DsbkException::class.java) { b.write(ByteArray(3)) }
            assertEquals(DsbkError.OUTPUT_LIMIT_EXCEEDED, error.error)
            a.abort()
            b.write(ByteArray(8) { 7 })
            b.commit()
            assertEquals(8, File(temporary.root, "b").length())
        }
    }

    @Test
    fun concurrentCloseAndCommitHaveOnlyCompleteOrAbortedOutcomes() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(24) { iteration ->
                val area = DsbkStagingArea(File(temporary.root, "parts-$iteration"), 64)
                val target = File(temporary.root, "active-$iteration").apply { writeText("old") }
                val sink = area.createSink(target)
                sink.write("complete".toByteArray())
                val barrier = CyclicBarrier(2)
                val commit = pool.submit<Boolean> {
                    barrier.await(5, TimeUnit.SECONDS)
                    runCatching { sink.commit() }.isSuccess
                }
                val close = pool.submit {
                    barrier.await(5, TimeUnit.SECONDS)
                    area.close()
                }
                val published = commit.get(10, TimeUnit.SECONDS)
                close.get(10, TimeUnit.SECONDS)
                assertEquals(if (published) "complete" else "old", target.readText())
                assertTrue(File(temporary.root, "parts-$iteration").listFiles()!!.none { it.name.endsWith(".part") })
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun masterRotationBlocksOtherWriterAndStaleInstanceCannotPublish() {
        val wrapper = SoftwareWrapper()
        val file = File(temporary.root, "master.key")
        val directory = File(temporary.root, "credentials")
        val firstMaster = WrappedMasterKeyStore(file, wrapper)
        val first = EncryptedCredentialStore(directory, firstMaster)
        val second = EncryptedCredentialStore(directory, WrappedMasterKeyStore(file, wrapper))
        val key = CredentialKey.of("concurrent.credential")
        val value = SecretBytes.fromBytes("before".toByteArray())
        val pool = Executors.newSingleThreadExecutor()
        try {
            first.put(key, value)
            val started = CountDownLatch(1)
            firstMaster.withExclusiveAccess {
                val write = pool.submit {
                    started.countDown()
                    second.put(key, value)
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                Thread.sleep(25)
                assertFalse(write.isDone)
                first.rebuildMasterKey()
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            // The second instance first observed the new generation, so its
            // operation is serialized AFTER rotation and must remain readable.
            first.get(key).let { loaded ->
                try { loaded.use { assertArrayEquals("before".toByteArray(), it) } } finally { loaded.close() }
            }
            second.get(key).close()
            first.rebuildMasterKey()
            val error = assertThrows(CredentialStoreException::class.java) { second.put(key, value) }
            assertEquals(CredentialStoreError.MASTER_KEY_UNAVAILABLE, error.error)
            assertTrue(directory.listFiles()!!.none { it.name.endsWith(".dsc") })
        } finally {
            value.close()
            first.close()
            second.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun missingMasterWithExistingRecordsNeverCreatesAnUnrelatedReplacement() {
        val file = File(temporary.root, "master.key")
        val directory = File(temporary.root, "credentials")
        val wrapper = SoftwareWrapper()
        val key = CredentialKey.of("existing")
        val value = SecretBytes.fromBytes(byteArrayOf(1, 2, 3))
        EncryptedCredentialStore(directory, WrappedMasterKeyStore(file, wrapper)).use { it.put(key, value) }
        check(file.delete())
        EncryptedCredentialStore(directory, WrappedMasterKeyStore(file, wrapper)).use { store ->
            val failure = assertThrows(CredentialStoreException::class.java) { store.put(key, value) }
            assertEquals(CredentialStoreError.MASTER_KEY_UNAVAILABLE, failure.error)
            assertFalse(file.exists())
        }
        value.close()
    }

    private class SoftwareWrapper : KeyWrapper {
        private var key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        override fun wrap(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.iv + cipher.doFinal(plaintext)
        }
        override fun unwrap(wrapped: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
            return cipher.doFinal(wrapped, 12, wrapped.size - 12)
        }
        override fun deleteKey() {
            key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        }
    }
}
