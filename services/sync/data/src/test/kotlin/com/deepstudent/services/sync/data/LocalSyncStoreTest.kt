package com.deepstudent.services.sync.data

import com.deepstudent.platform.security.*
import com.deepstudent.services.sync.api.*
import com.deepstudent.services.sync.backend.SyncFailure
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalSyncStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val wrapper = object : KeyWrapper {
        override fun wrap(plaintext: ByteArray) = plaintext.copyOf()
        override fun unwrap(wrapped: ByteArray) = wrapped.copyOf()
    }
    private fun credentials() = EncryptedCredentialStore(File(temp.root, "secrets"), WrappedMasterKeyStore(File(temp.root, "master"), wrapper))
    private fun config(store: LocalSyncStore, root: String) = SyncConfiguration("https://example.test", "u", root, store.getOrCreate(), "instance")
    private fun password(store: LocalSyncStore): String {
        val secret = store.password()
        return try { secret.use { it.toString(Charsets.UTF_8) } } finally { secret.close() }
    }

    @Test fun realEncryptedStoreReopensAndConcurrentUpdatesStayConsistent() {
        val first = LocalSyncStore(File(temp.root, "sync"), credentials())
        val second = LocalSyncStore(File(temp.root, "sync"), credentials())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(first, second).mapIndexed { index, store -> pool.submit { repeat(10) { store.save(config(store, "r$index"), "p$index".toCharArray()) } } }
            futures.forEach { it.get() }
        } finally { pool.shutdownNow() }
        val reopened = LocalSyncStore(File(temp.root, "sync"), credentials())
        val root = reopened.read()!!.root
        assertEquals("p${root.last()}", password(reopened))
        assertFalse(File(temp.root, "sync/configuration.json").readText().contains("p0"))
    }

    @Test fun failedWriteKeepsPreviousSecret() {
        val dir = File(temp.root, "sync")
        val store = LocalSyncStore(dir, credentials())
        store.save(config(store, "old"), "old-password".toCharArray())
        val oversized = config(store, "x".repeat(70000))
        assertThrows(SyncFailure::class.java) { store.save(oversized, "new-password".toCharArray()) }
        assertEquals("old", store.read()!!.root)
        assertEquals("old-password", password(store))
    }

    @Test fun restoreRotationDoesNotAdoptAnOldWriterOrClearANewerBindingOnRetry() {
        val directory = File(temp.root, "sync")
        val store = LocalSyncStore(directory, credentials())
        val next = UUID.randomUUID().toString()
        assertFalse(store.rotateWriterForRestore(next))
        assertFalse(File(directory, "device-id").exists())
        val former = config(store, "old")
        store.save(former, "previous".toCharArray())
        assertTrue(store.rotateWriterForRestore(next))
        assertNotEquals(former.deviceId, next)
        assertEquals(next, store.getOrCreate())
        assertNull(store.read())
        val replacement = config(store, "rejoined")
        store.save(replacement, "fresh".toCharArray())
        assertTrue(store.rotateWriterForRestore(next))
        assertEquals(replacement, store.read())
        assertEquals("fresh", password(store))
    }

    @Test fun missingCorruptIdentityAndOversizeFailClosed() {
        val dir = File(temp.root, "sync")
        val store = LocalSyncStore(dir, credentials())
        store.save(config(store, "r"), "password".toCharArray())
        val identity = File(dir, "device-id")
        identity.delete()
        assertEquals(SyncError.STORAGE, assertThrows(SyncFailure::class.java) { store.read() }.error)
        assertFalse(identity.exists())
        identity.writeText("bad")
        assertEquals(SyncError.STORAGE, assertThrows(SyncFailure::class.java) { store.getOrCreate() }.error)
        identity.writeText("x".repeat(65537))
        assertEquals(SyncError.LIMIT_EXCEEDED, assertThrows(SyncFailure::class.java) { store.getOrCreate() }.error)
    }

    @Test fun symlinkAndCredentialErrorsAreRejected() {
        val dir = File(temp.root, "sync")
        val store = LocalSyncStore(dir, credentials())
        store.save(config(store, "r"), "password".toCharArray())
        File(dir, "device-id").delete()
        Files.createSymbolicLink(File(dir, "device-id").toPath(), File(temp.root, "master").toPath())
        assertEquals(SyncError.STORAGE, assertThrows(SyncFailure::class.java) { store.read() }.error)
        val unavailable = object : CredentialStore {
            override fun put(key: CredentialKey, value: SecretBytes) { throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE) }
            override fun get(key: CredentialKey): SecretBytes = throw CredentialStoreException(CredentialStoreError.INTEGRITY_FAILURE)
            override fun delete(key: CredentialKey) = Unit
            override fun close() = Unit
        }
        val other = LocalSyncStore(File(temp.root, "other"), unavailable)
        val failure = assertThrows(SyncFailure::class.java) { other.save(config(other, "r"), charArrayOf()) }
        assertEquals(SyncError.CREDENTIAL_UNAVAILABLE, failure.error)
        assertNull(failure.cause)
    }
}
