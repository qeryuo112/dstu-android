package com.deepstudent.platform.security

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MasterKeyRecoveryContractTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = CredentialKey.of("recovery.test")

    @Test
    fun cleanAndHealthyProbeDoNotCreateKeysOrAuthorizeReset() {
        val wrapper = RecoveryWrapper()
        SecurityFactory.create(temporary.root, wrapper).use { ports ->
            ports.requireMasterKeyUsable()
            assertEquals(0, wrapper.wraps)
            assertFalse(File(temporary.root, "master.key").exists())
            assertEquals(CredentialStoreError.MASTER_KEY_RESET_NOT_ALLOWED, failure { ports.rebuildLostMasterKey() }.error)
            assertEquals(0, wrapper.wraps)
            val value = SecretBytes.fromBytes(byteArrayOf(1, 2, 3))
            try { ports.credentials.put(key, value) } finally { value.close() }
            val master = File(temporary.root, "master.key").readBytes()
            val record = recordFile()
            val before = record.readBytes()
            ports.requireMasterKeyUsable()
            assertEquals(CredentialStoreError.MASTER_KEY_RESET_NOT_ALLOWED, failure { ports.rebuildLostMasterKey() }.error)
            assertArrayEquals(master, File(temporary.root, "master.key").readBytes())
            assertArrayEquals(before, record.readBytes())
            assertEquals(1, wrapper.wraps)
        }
    }

    @Test
    fun transientAndRecoveredProviderErrorsNeverAuthorizeDestruction() {
        val wrapper = RecoveryWrapper()
        val events = mutableListOf<SecurityAuditEvent>()
        SecurityFactory.create(temporary.root, wrapper, audit = SecurityAudit { events.add(it) }).use { ports ->
            save(ports)
            val record = recordFile()
            val before = record.readBytes()
            val master = File(temporary.root, "master.key").readBytes()
            wrapper.failure = MasterKeyFailure.UNCONFIRMED
            assertEquals(MasterKeyFailure.UNCONFIRMED, failure { ports.requireMasterKeyUsable() }.masterKeyFailure)
            assertEquals(MasterKeyFailure.UNCONFIRMED, failure { ports.rebuildLostMasterKey() }.masterKeyFailure)
            assertArrayEquals(before, record.readBytes())
            assertArrayEquals(master, File(temporary.root, "master.key").readBytes())
            wrapper.failure = MasterKeyFailure.PERMANENTLY_LOST
            assertEquals(MasterKeyFailure.PERMANENTLY_LOST, failure { ports.requireMasterKeyUsable() }.masterKeyFailure)
            // It becomes healthy between the probe and the user's confirmation.
            wrapper.failure = null
            assertEquals(CredentialStoreError.MASTER_KEY_RESET_NOT_ALLOWED, failure { ports.rebuildLostMasterKey() }.error)
            assertArrayEquals(before, record.readBytes())
            assertArrayEquals(master, File(temporary.root, "master.key").readBytes())
            assertTrue(events.any { it.operation == SecurityOperation.MASTER_KEY_PROBE && it.masterKeyFailure == MasterKeyFailure.UNCONFIRMED })
            val text = events.joinToString("\n")
            assertFalse(text.contains("recovery.test"))
            assertFalse(text.contains("private-value"))
            assertTrue(text.contains("correlationId="))
            assertTrue(text.contains("phase=RESET_RECHECK"))
            assertTrue(text.contains("elapsedMs="))
        }
    }

    @Test
    fun confirmedLossIsRecheckedAndExplicitlyReset() {
        val wrapper = RecoveryWrapper()
        val events = mutableListOf<SecurityAuditEvent>()
        SecurityFactory.create(temporary.root, wrapper, audit = SecurityAudit { events.add(it) }).use { ports ->
            save(ports)
            val record = recordFile()
            wrapper.failure = MasterKeyFailure.PERMANENTLY_LOST
            assertEquals(MasterKeyFailure.PERMANENTLY_LOST, failure { ports.requireMasterKeyUsable() }.masterKeyFailure)
            assertTrue(record.exists())
            ports.rebuildLostMasterKey()
            assertFalse(record.exists())
            assertEquals(2, wrapper.wraps)
            ports.requireMasterKeyUsable()
            assertEquals(CredentialStoreError.NOT_FOUND, failure { ports.credentials.get(key) }.error)
            save(ports)
            val reset = events.filter { it.operation == SecurityOperation.MASTER_KEY_RESET }
            assertEquals(1, reset.mapNotNull { it.correlationId }.distinct().size)
            assertEquals(listOf(SecurityPhase.RESET_RECHECK, SecurityPhase.RESET_RECHECK, SecurityPhase.RESET_APPLY, SecurityPhase.RESET_APPLY), reset.map { it.phase })
        }
    }

    @Test
    fun missingMasterWithRecordsIsLossButAnUnsafeMasterPathIsUnconfirmed() {
        val wrapper = RecoveryWrapper()
        SecurityFactory.create(temporary.root, wrapper).use { ports ->
            save(ports)
            val master = File(temporary.root, "master.key")
            assertTrue(master.delete())
            assertEquals(MasterKeyFailure.PERMANENTLY_LOST, failure { ports.requireMasterKeyUsable() }.masterKeyFailure)
            val victim = File(temporary.root, "victim").apply { writeText("safe") }
            Files.createSymbolicLink(master.toPath(), victim.toPath())
            assertEquals(MasterKeyFailure.UNCONFIRMED, failure { ports.rebuildLostMasterKey() }.masterKeyFailure)
            assertEquals("safe", victim.readText())
            assertTrue(recordFile().exists())
        }
    }

    private fun save(ports: SecurityPorts) {
        val value = SecretBytes.fromBytes("private-value".toByteArray())
        try { ports.credentials.put(key, value) } finally { value.close() }
    }

    private fun recordFile(): File = File(temporary.root, "credentials").listFiles()!!.single { it.name.endsWith(".dsc") }

    private fun failure(block: () -> Unit): CredentialStoreException =
        assertThrows(CredentialStoreException::class.java, block)
}

private class RecoveryWrapper : KeyWrapper {
    var failure: MasterKeyFailure? = null
    var wraps = 0
    override fun wrap(plaintext: ByteArray): ByteArray {
        wraps++
        failure = null
        return plaintext.map { (it.toInt() xor 0x55).toByte() }.toByteArray()
    }
    override fun unwrap(wrapped: ByteArray): ByteArray {
        when (failure) {
            MasterKeyFailure.PERMANENTLY_LOST -> throw masterKeyLost()
            MasterKeyFailure.UNCONFIRMED -> throw masterKeyUnconfirmed()
            null -> return wrapped.map { (it.toInt() xor 0x55).toByte() }.toByteArray()
        }
    }
    override fun deleteKey() { failure = null }
}
