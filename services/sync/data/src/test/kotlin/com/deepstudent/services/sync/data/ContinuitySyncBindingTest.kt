package com.deepstudent.services.sync.data

import com.deepstudent.platform.security.CredentialKey
import com.deepstudent.platform.security.CredentialStore
import com.deepstudent.platform.security.CredentialStoreError
import com.deepstudent.platform.security.CredentialStoreException
import com.deepstudent.platform.security.SecretBytes
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.ProviderFactory
import com.deepstudent.services.sync.backend.SyncFailure
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContinuitySyncBindingTest {
    @get:Rule val temp = TemporaryFolder()
    private val credentials = MemoryCredentials()
    private val directory by lazy { File(temp.root, "sync") }
    private fun store() = LocalSyncStore(directory, credentials)
    private fun oldConfig(store: LocalSyncStore) = SyncConfiguration("https://former.test/dav", "u", "old", store.getOrCreate(), "previous")
    private fun config(writer: String = "legacy_writer:one", instance: String = "old-instance") =
        SyncConfiguration("https://EXAMPLE.test:443/dav", "u", "work files", writer, instance)
    private fun binding(operation: String = UUID.randomUUID().toString()) =
        ContinuitySyncBinding(operation, "legacy_writer:one", "old-instance", "https://example.test/dav/work%20files/")
    private fun secret(store: LocalSyncStore): String = store.password().let { secret ->
        try { secret.use { it.toString(Charsets.UTF_8) } } finally { secret.close() }
    }
    private fun error(expected: SyncError, block: () -> Unit) =
        assertEquals(expected, assertThrows(SyncFailure::class.java, block).error)

    @Test fun repositoryUrlIsPureAndMatchesTheProviderBaseRules() {
        val value = config()
        assertEquals("https://example.test/dav/work%20files/", ContinuitySyncBinding.repositoryUrl(value))
        assertEquals("https://example.test/dav/work%20files/", ContinuitySyncBinding.repositoryUrl(
            value.copy(endpoint = "https://example.test/dav/", root = "work files/")))
        assertEquals("https://example.test/dav/", ContinuitySyncBinding.repositoryUrl(value.copy(root = "")))
        assertEquals("https://example.test/dav/%252F/", ContinuitySyncBinding.repositoryUrl(value.copy(root = "%2F")))
        assertEquals("https://example.test/dav%20space/work%20files/", ContinuitySyncBinding.repositoryUrl(
            value.copy(endpoint = "https://example.test/dav space")))
        for (endpoint in listOf("http://example.test", "https://a:b@example.test", "https://@example.test", "https:@example.test", "https://example.test?q=x", "https://example.test/#f")) {
            error(SyncError.INVALID_CONFIGURATION) { ContinuitySyncBinding.repositoryUrl(value.copy(endpoint = endpoint)) }
        }
        for (root in listOf("../escape", "a//b", "/absolute", "a\\b")) {
            error(SyncError.INVALID_CONFIGURATION) { ContinuitySyncBinding.repositoryUrl(value.copy(root = root)) }
        }
        assertFalse(directory.exists())
        assertTrue(credentials.values.isEmpty())
    }

    @Test fun nonUuidWriterIsAdoptedOnlyThroughPublishedBindingAndRequiresRepairing() {
        val first = store()
        val old = oldConfig(first)
        first.save(old, "old-password".toCharArray())
        val accepted = binding()
        first.adoptWriterForContinuity(accepted)
        assertEquals(old.deviceId, File(directory, "device-id").readText())
        assertTrue(File(directory, "continuity-binding-v1.json").isFile)
        assertEquals(accepted.writerId, first.getOrCreate())
        assertNull(first.read())
        assertTrue(credentials.values.isEmpty())
        assertFalse(accepted.toString().contains("example"))

        val reopened = store()
        assertEquals(accepted.writerId, reopened.getOrCreate())
        reopened.save(config(), "new-password".toCharArray())
        assertEquals(config(), store().read())
        assertEquals("new-password", secret(store()))
        reopened.adoptWriterForContinuity(accepted)
        assertEquals(config(), reopened.read())
        assertEquals("new-password", secret(reopened))
        error(SyncError.INVALID_CONFIGURATION) { reopened.adoptWriterForContinuity(accepted.copy(writerId = "other")) }
    }

    @Test fun aContinuityBindingDeniesCreateAndOtherUrlsBeforeOpeningProvider() = runBlocking {
        val store = store()
        store.adoptWriterForContinuity(binding())
        var opens = 0
        val provider = object : ProviderFactory {
            override fun open(endpoint: String, username: String, root: String, password: CharArray): Nothing {
                opens++
                throw SyncFailure(SyncError.NETWORK)
            }
        }
        val pairing = PairingService(store, store, provider)
        val wrong = PairingRequest("https://other.test/dav/", "u", "work files", charArrayOf('x'))
        val correct = { PairingRequest("https://example.test/dav/", "u", "work files", charArrayOf('x')) }
        assertEquals(SyncResult.Failure(SyncError.INVALID_CONFIGURATION), pairing.pair(PairingMode.CREATE, correct()))
        assertEquals(SyncResult.Failure(SyncError.INVALID_CONFIGURATION), pairing.pair(PairingMode.JOIN, wrong))
        assertEquals(0, opens)
        assertEquals(SyncResult.Failure(SyncError.NETWORK), pairing.pair(PairingMode.JOIN, correct()))
        assertEquals(1, opens)
    }

    @Test fun bindingRestrictsNewAndReopenedConfigurationToSameRepositoryAndInstance() {
        val store = store()
        val binding = binding()
        store.adoptWriterForContinuity(binding)
        val correct = config()
        store.save(correct, "safe".toCharArray())
        val invalid = listOf(
            correct.copy(endpoint = "https://other.test/dav"), correct.copy(root = "other"),
            correct.copy(instanceId = "other"), correct.copy(endpoint = "http://example.test/dav"),
            correct.copy(endpoint = "https://example.test/dav?password=bad"), correct.copy(deviceId = UUID.randomUUID().toString()),
        )
        invalid.forEach { candidate -> error(SyncError.INVALID_CONFIGURATION) { store.save(candidate, "bad".toCharArray()) } }
        assertEquals(correct, store.read())
        assertEquals("safe", secret(store))

        val file = File(directory, "configuration.json")
        val original = file.readText()
        for ((from, to) in listOf("old-instance" to "other", "EXAMPLE.test" to "other.test", "work files" to "other-root")) {
            file.writeText(original.replace(from, to))
            error(SyncError.STORAGE) { store().read() }
        }
        file.writeText(original)
        assertEquals(correct, store().read())
    }

    @Test fun ordinaryRotationRemovesBindingAndAllowsAnotherRepository() {
        val store = store()
        val binding = binding()
        store.adoptWriterForContinuity(binding)
        assertFalse(File(directory, "device-id").exists())
        store.save(config(), "before".toCharArray())
        val operation = UUID.randomUUID().toString()
        assertTrue(store.rotateWriterForRestore(operation))
        assertFalse(File(directory, "continuity-binding-v1.json").exists())
        assertEquals(operation, store().getOrCreate())
        assertNull(store().read())
        val next = SyncConfiguration("https://different.test", "user", "new", operation, "new-instance")
        store.save(next, "after".toCharArray())
        assertTrue(store().rotateWriterForRestore(operation))
        assertEquals(next, store().read())
        assertEquals("after", secret(store))
    }

    @Test fun interruptedPublicationHasNoUsableOldConfigAndRetriesIdempotently() {
        for (stage in listOf("adoption-pending", "configuration-removed", "continuity-published")) {
            val dir = File(temp.newFolder(), "sync")
            val first = LocalSyncStore(dir, credentials)
            val old = oldConfig(first)
            first.save(old, "old".toCharArray())
            val chosen = binding()
            first.continuityCheckpoint = { if (it == stage) throw IOException("simulated interruption") }
            assertThrows(IOException::class.java) { first.adoptWriterForContinuity(chosen) }
            // A leftover file is never a published reference, even if it contains the future writer.
            File(dir, "sync-residual.tmp").writeText(chosen.writerId)
            val reopened = LocalSyncStore(dir, credentials)
            if (stage == "continuity-published") {
                assertNull(reopened.read())
                assertEquals(chosen.writerId, reopened.getOrCreate())
            } else {
                error(SyncError.STORAGE) { reopened.read() }
                error(SyncError.STORAGE) { reopened.getOrCreate() }
                error(SyncError.STORAGE) { reopened.save(config(), "bad".toCharArray()) }
                error(SyncError.STORAGE) { reopened.clear() }
                error(SyncError.STORAGE) { reopened.adoptWriterForContinuity(binding()) }
                error(SyncError.STORAGE) { reopened.rotateWriterForRestore(UUID.randomUUID().toString()) }
            }
            if (stage == "continuity-published") reopened.save(config(), "new".toCharArray())
            reopened.adoptWriterForContinuity(chosen)
            if (stage != "continuity-published") reopened.save(config(), "new".toCharArray())
            LocalSyncStore(dir, credentials).adoptWriterForContinuity(chosen)
            assertEquals(config(), reopened.read())
            assertEquals("new", secret(reopened))
        }
    }

    @Test fun interruptedOrdinaryRotationCannotUseAStaleCredentialReference() {
        for (stage in listOf("rotation-pending", "rotation-configuration-removed", "rotation-identity-published")) {
            val dir = File(temp.newFolder(), "sync")
            val first = LocalSyncStore(dir, credentials)
            first.adoptWriterForContinuity(binding())
            first.save(config(), "old".toCharArray())
            val op = UUID.randomUUID().toString()
            first.continuityCheckpoint = { if (it == stage) throw IOException("simulated interruption") }
            assertThrows(IOException::class.java) { first.rotateWriterForRestore(op) }
            val reopened = LocalSyncStore(dir, credentials)
            error(SyncError.STORAGE) { reopened.read() }
            error(SyncError.STORAGE) { reopened.getOrCreate() }
            error(SyncError.STORAGE) { reopened.save(config(), "bad".toCharArray()) }
            error(SyncError.STORAGE) { reopened.rotateWriterForRestore(UUID.randomUUID().toString()) }
            error(SyncError.STORAGE) { reopened.adoptWriterForContinuity(binding()) }
            assertTrue(reopened.rotateWriterForRestore(op))
            assertEquals(op, LocalSyncStore(dir, credentials).getOrCreate())
            assertNull(reopened.read())
        }
    }

    @Test fun malformedBindingAndUnpublishedNonUuidDeviceIdFailClosed() {
        val first = store()
        first.getOrCreate()
        File(directory, "device-id").writeText("legacy_writer:one")
        File(directory, "sync-residual.tmp").writeText("legacy_writer:one")
        error(SyncError.STORAGE) { first.getOrCreate() }
        File(directory, "device-id").writeText(UUID.randomUUID().toString())
        val binding = binding()
        first.adoptWriterForContinuity(binding)
        val file = File(directory, "continuity-binding-v1.json")
        val valid = file.readText()
        for (bad in listOf(valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"version\":1", "\"version\":1,\"extra\":true"), "{}")) {
            file.writeText(bad)
            error(SyncError.STORAGE) { store().getOrCreate() }
        }
        file.delete()
        Files.createSymbolicLink(file.toPath(), File(directory, "device-id").toPath())
        error(SyncError.STORAGE) { store().getOrCreate() }
    }

    @Test fun staleConfigurationCannotReuseDifferentWriterOrRepositoryAfterPublication() {
        val store = store()
        val old = oldConfig(store)
        store.save(old, "old".toCharArray())
        val before = File(directory, "configuration.json").readText()
        val accepted = binding()
        store.adoptWriterForContinuity(accepted)
        // A stale ref cannot use the newly published identity, even if placed at the old path.
        File(directory, "configuration.json").writeText(before)
        error(SyncError.STORAGE) { store().read() }
        error(SyncError.STORAGE) { store().password() }
        File(directory, "configuration.json").delete()
        assertNull(store().read())
    }

    @Test fun deletedOrMalformedBindingCannotAuthorizeOldNonUuidWriter() {
        val store = store()
        val accepted = binding()
        store.adoptWriterForContinuity(accepted)
        store.save(config(), "fresh".toCharArray())
        val file = File(directory, "continuity-binding-v1.json")
        file.delete()
        // No legacy device-id was ever published; a configuration is not itself authority.
        error(SyncError.STORAGE) { store().getOrCreate() }
        error(SyncError.STORAGE) { store().read() }
    }

    @Test fun invalidUntrustedInputsDoNotReplacePriorConfig() {
        val store = store()
        val previous = oldConfig(store)
        store.save(previous, "old".toCharArray())
        val good = binding()
        val bad = listOf(good.copy(operationId = "not-uuid"), good.copy(writerId = "../writer"),
            good.copy(instanceId = "bad id"), good.copy(repositoryUrl = "http://example.test/dav/"),
            good.copy(repositoryUrl = "https://user:password@example.test/dav/"),
            good.copy(repositoryUrl = "https://example.test/dav"), good.copy(repositoryUrl = "https://example.test/dav/?q=x"))
        bad.forEach { value -> error(SyncError.INVALID_CONFIGURATION) { store.adoptWriterForContinuity(value) } }
        assertEquals(previous, store.read())
        assertEquals("old", secret(store))
    }

    private class MemoryCredentials : CredentialStore {
        val values = mutableMapOf<CredentialKey, ByteArray>()
        override fun put(key: CredentialKey, value: SecretBytes) { values[key] = value.use { it.copyOf() } }
        override fun get(key: CredentialKey): SecretBytes = SecretBytes.fromBytes(values[key]?.copyOf()
            ?: throw CredentialStoreException(CredentialStoreError.NOT_FOUND))
        override fun delete(key: CredentialKey) { values.remove(key) }
        override fun close() = Unit
    }
}
