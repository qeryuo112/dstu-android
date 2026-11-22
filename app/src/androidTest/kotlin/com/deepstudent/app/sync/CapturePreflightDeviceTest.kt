package com.deepstudent.app.sync

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.security.AndroidKeystoreKeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.ProviderFactory
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.data.LocalSyncStore
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapturePreflightDeviceTest {
    @Test fun captureDriftStopsTheRealTransferBeforeAnyNetworkOrJournal(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.canonicalFile.toPath(), "s204-app-").toFile()
        val alias = "com.deepstudent.app.sync.capture.${UUID.randomUUID()}"
        val source = BundledMigrationSource(context)
        try {
            SecurityFactory.create(root.resolve("security"), alias).use { security ->
                val config = LocalSyncStore(root.resolve("sync"), security.credentials)
                config.save(SyncConfiguration("https://fixture.invalid", "fixture-user", "root", config.getOrCreate(), "fixture-instance"),
                    "disposable fixture password".toCharArray())
                DatabaseRegistry.openShared(root.resolve("vfs.db"), SharedDatabase.VFS, source).use { database ->
                    val provider = NetworkBoundary()
                    val lockDirectory = root.resolve("locks").also { check(it.mkdir()) }
                    val transfer = MainSyncFactory(RepositoryEncryption(), source).open(
                        database, root.resolve("blobs"), config, provider, this, lockDirectory,
                        incomingDecksDatabase = { throw AssertionError("opened unrelated database before validation") },
                    )
                    // A healthy database reaches our fake boundary, without accessing a real provider.
                    assertEquals(SyncResult.Failure(SyncError.NETWORK), transfer.synchronize())
                    assertEquals(1, provider.opens)
                    database.execute("DROP TRIGGER trg__change_log_notes_update")
                    val before = database.query("SELECT id,sync_version FROM __change_log ORDER BY id") { it.long(0) to it.long(1) }
                    assertEquals(SyncResult.Failure(SyncError.STORAGE), transfer.synchronize())
                    assertEquals(1, provider.opens)
                    assertEquals(0L, database.query("SELECT count(*) FROM sqlite_master WHERE name LIKE '__android_sync_%'") { it.long(0) }.single())
                    assertEquals(before, database.query("SELECT id,sync_version FROM __change_log ORDER BY id") { it.long(0) to it.long(1) })
                    Log.i("DstuS204Test", "driftBlockedBeforeNetwork=true journalUncreated=true pendingUnchanged=true")
                }
            }
        } finally {
            AndroidKeystoreKeyWrapper(alias).deleteKey()
            root.deleteRecursively()
        }
    }

    private class NetworkBoundary : ProviderFactory {
        var opens = 0
            private set
        override fun open(endpoint: String, username: String, root: String, password: CharArray): ObjectProvider {
            opens++
            throw SyncFailure(SyncError.NETWORK)
        }
    }
}
