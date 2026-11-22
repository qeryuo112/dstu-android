package com.deepstudent.app.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.app.security.MainSecurityResources
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.platform.security.AndroidKeystoreKeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.data.LocalSyncStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrdinaryBackupIdentityDeviceTest {
    @get:Rule val foreground = BackupForegroundRule()
    private val fixture = LegacyMobileFixtureSupport()
    private val target = fixture.target
    private val sync get() = target.noBackupFilesDir.canonicalFile.resolve("sync")
    private val alias = "fixture.s215.${UUID.randomUUID()}"
    private val identity = OrdinaryRestoreIdentity(target, ::openFixtureSecurity)

    @After fun close() {
        try { AndroidKeystoreKeyWrapper(alias).deleteKey() } finally { fixture.close() }
    }

    @Test fun boundOrdinaryRestoreRotatesWriterOnlyAfterTheColdSlotCommit(): Unit = runBlocking {
        val old = fixture.seedLocalState()
        val oldWriter = createBinding()
        seedConsumption(old.root.resolve("databases/vfs.db"))
        val source = fixture.original("standard")
        val uri = fixture.uri(source)
        val plan = fixture.reader.read(uri)
        val coordinator = BackupRestoreCoordinator(target, identity).also { it.beforeBusinessOpen() }
        coordinator.schedule(uri, plan)
        assertEquals(BackupRestoreUiStatus.QUEUED, coordinator.status.value)
        assertEquals(oldWriter, sync.resolve("device-id").readText())
        assertTrue(sync.resolve("configuration.json").isFile)
        val operation = checkNotNull(PendingBackupRestore(target.noBackupFilesDir.resolve("backup-restore")).request()).id
        val startup = BackupRestoreCoordinator(target, identity).also { it.beforeBusinessOpen() }
        assertEquals(BackupRestoreUiStatus.APPLIED_REPAIR_SYNC, startup.status.value)
        assertEquals(operation, sync.resolve("device-id").readText())
        assertNotEquals(oldWriter, operation)
        assertFalse(sync.resolve("configuration.json").exists())
        assertTrue(fixture.scratch.resolve("seed-keys/credentials").listFiles().orEmpty().none { it.name.endsWith(".dsc") })
        assertTrue(identity.finish(operation))
        startup.requireStableSyncIdentity()
        MainStorageResources.open(target).use { storage ->
            assertNotEquals(old.root.canonicalFile, storage.activeRoot)
            val seed = fixture.seeds
            val note = storage.notes.get(seed.getJSONArray("noteIds").getString(0))
            assertEquals(seed.getJSONArray("noteContents").getString(0), note?.content)
            assertEquals(0L, consumptionCount(storage.activeRoot.resolve("databases/vfs.db")))
        }
        assertEquals(1L, consumptionCount(old.root.resolve("databases/vfs.db")))
    }

    @Test fun rejectedArchiveLeavesTheBindingAndDeviceUnchanged(): Unit = runBlocking {
        val old = fixture.seedLocalState()
        val oldWriter = createBinding()
        val original = fixture.original("standard")
        val confirmed = fixture.reader.read(fixture.uri(original))
        val changed = fixture.alterArchive(original, "corrupt.zip", corruptBlob = true)
        val coordinator = BackupRestoreCoordinator(target, identity).also { it.beforeBusinessOpen() }
        assertFalse(coordinator.schedule(fixture.uri(changed), confirmed))
        assertEquals(BackupRestoreUiStatus.PACK_CHANGED, coordinator.status.value)
        assertEquals(oldWriter, sync.resolve("device-id").readText())
        assertTrue(sync.resolve("configuration.json").isFile)
        MainStorageResources.open(target).use { storage ->
            assertEquals(old.root.canonicalFile, storage.activeRoot)
            assertEquals("old device data", storage.notes.get(old.noteId)?.content)
        }
    }

    @Test fun restoredOrdinaryWriterJoinsRealTlsRepositoryWithNewCredentials(): Unit = runBlocking {
        ManagedContinuityTestRepository().use { repo ->
            fixture.seedLocalState()
            val previous = openFixtureSecurity().use { ports ->
                val store = LocalSyncStore(sync, ports.credentials)
                val service = PairingService(store, store, repo.providerFactory, AndroidOperationLogger)
                val joined = service.pair(PairingMode.JOIN, PairingRequest(repo.url, ManagedContinuityTestRepository.USER, "",
                    ManagedContinuityTestRepository.OLD_PASSWORD.toCharArray()))
                assertTrue(joined is SyncResult.Success)
                (joined as SyncResult.Success).value
            }
            assertEquals(ManagedContinuityTestRepository.INSTANCE, previous.instanceId)
            repo.revokeOldAndRecordSequence()
            val uri = fixture.uri(fixture.original("standard"))
            val plan = fixture.reader.read(uri)
            val queued = BackupRestoreCoordinator(target, identity).also { it.beforeBusinessOpen() }
            assertTrue(queued.schedule(uri, plan))
            val operation = checkNotNull(PendingBackupRestore(target.noBackupFilesDir.resolve("backup-restore")).request()).id
            val restored = BackupRestoreCoordinator(target, identity).also { it.beforeBusinessOpen() }
            assertEquals(BackupRestoreUiStatus.APPLIED_REPAIR_SYNC, restored.status.value)
            assertNotEquals(previous.deviceId, operation)
            assertTrue(fixture.scratch.resolve("seed-keys/credentials").listFiles().orEmpty().none { it.name.endsWith(".dsc") })
            openFixtureSecurity().use { ports ->
                val store = LocalSyncStore(sync, ports.credentials)
                assertEquals(operation, store.getOrCreate())
                assertNull(store.read())
                val service = PairingService(store, store, repo.providerFactory, AndroidOperationLogger)
                val old = service.pair(PairingMode.JOIN, PairingRequest(repo.url, ManagedContinuityTestRepository.USER, "",
                    ManagedContinuityTestRepository.OLD_PASSWORD.toCharArray()))
                assertEquals(SyncResult.Failure(SyncError.AUTHENTICATION), old)
                assertNull(store.read())
                val joined = service.pair(PairingMode.JOIN, PairingRequest(repo.url, ManagedContinuityTestRepository.USER, "",
                    ManagedContinuityTestRepository.NEW_PASSWORD.toCharArray()))
                assertTrue(joined is SyncResult.Success)
                val config = (joined as SyncResult.Success).value
                assertEquals(operation, config.deviceId)
                assertNotEquals(previous.deviceId, config.deviceId)
                assertEquals(previous.instanceId, config.instanceId)
                val reopened = LocalSyncStore(sync, ports.credentials)
                assertEquals(config, reopened.read())
                val secret = reopened.password(config)
                try { secret.use { assertEquals(ManagedContinuityTestRepository.NEW_PASSWORD, it.toString(Charsets.UTF_8)) } }
                finally { secret.close() }
            }
        }
    }

    private fun createBinding(): String = openFixtureSecurity().use { ports ->
        val store = LocalSyncStore(sync, ports.credentials)
        store.getOrCreate().also { id ->
            store.save(SyncConfiguration("https://example.invalid", "fixture", "fixture", id, "fixture-instance"), "fixture".toCharArray())
        }
    }

    private fun openFixtureSecurity() = MainSecurityResources.open(target) { _, audit ->
        SecurityFactory.create(fixture.scratch.canonicalFile.resolve("seed-keys"), alias, audit = audit)
    }

    private fun seedConsumption(vfs: java.io.File) {
        DatabaseRegistry.openShared(vfs, SharedDatabase.VFS, BundledMigrationSource(target)).use { db ->
            db.execute("CREATE TABLE IF NOT EXISTS __android_sync_cursor_v2(instance TEXT NOT NULL,device TEXT NOT NULL,seq INTEGER NOT NULL,PRIMARY KEY(instance,device))")
            db.execute("INSERT INTO __android_sync_cursor_v2(instance,device,seq) VALUES(?,?,?)",
                listOf(SqlValue.Text("fixture-instance"), SqlValue.Text("old-peer"), SqlValue.Integer(12)))
        }
    }

    private fun consumptionCount(vfs: java.io.File): Long =
        DatabaseRegistry.openShared(vfs, SharedDatabase.VFS, BundledMigrationSource(target)).use { db ->
            db.query("SELECT count(*) FROM __android_sync_cursor_v2") { it.long(0) }.single()
        }
}
