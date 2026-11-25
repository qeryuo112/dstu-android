package com.deepstudent.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.id.Digests
import com.deepstudent.features.settings.api.PortableSettingsSnapshots
import com.deepstudent.features.settings.api.RemotePreferenceRow
import com.deepstudent.features.settings.api.RemotePreferenceRows
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.resources.data.SyncBlobStore
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.transfer.BlobTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.InputStream

@RunWith(AndroidJUnit4::class)
class ApplyEdgesDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = BundledMigrationSource(context)

    @get:Rule val temp = TemporaryFolder(context.cacheDir.canonicalFile)

    @Test fun portablePreferencesRoundtripViaTheirSeparateWireContractAndMigratedRows() {
        val timestamp = "2026-01-01T00:00:00.000Z"
        DatabaseFactory.openShared(temp.root.resolve("source.db"), SharedDatabase.MISTAKES, source).use { from ->
            from.execute(
                "INSERT OR REPLACE INTO settings(key,value,updated_at) VALUES('theme','dark','$timestamp')," +
                    "('fixture.local.secret','synthetic-canary','$timestamp')",
            )
            val snapshot = PortableSettingsSnapshots.capture { key -> from.preference(key) }
            val wire = RemotePreferenceRows.encode(snapshot.rows.map { RemotePreferenceRow(it.key, it.value, timestamp) })
            assertFalse(wire.contains("synthetic-canary"))
            val rows = RemotePreferenceRows.decode(wire)
            assertEquals("dark", rows.single { it.key == "theme" }.value)
            DatabaseFactory.openShared(temp.root.resolve("target.db"), SharedDatabase.MISTAKES, source).use { to ->
                to.transaction {
                    rows.forEach { row ->
                        execute(
                            "INSERT OR REPLACE INTO settings(key,value,updated_at) VALUES(?,?,?)",
                            listOf(row.key, row.value, row.updatedAt).map(SqlValue::Text),
                        )
                    }
                }
                val applied = PortableSettingsSnapshots.capture { key -> to.preference(key) }
                assertEquals(snapshot.rows.single { it.key == "theme" }, applied.rows.single { it.key == "theme" })
                assertEquals(null, to.preference("fixture.local.secret"))
            }
        }
    }

    @Test fun interruptedDownloadCleansRealFileStageAndRetryMaterializesVerifiedBytes() = runBlocking {
        val staging = temp.root.resolve("sync-stage")
        val files = temp.root.resolve("vfs_blobs")
        val body = ByteArray(384 * 1024) { (it % 251).toByte() }
        val hash = Digests.sha256Hex(body)
        val path = "${hash.take(2)}/$hash.bin"
        val remote = InterruptedAsset(body, hash, path)
        DatabaseFactory.openShared(temp.root.resolve("local.db"), SharedDatabase.VFS, source).use { db ->
            val transfer = BlobTransfer(remote, DatabaseSyncBlobs(db, SyncBlobStore(files)), stageDirectory = staging)
            var cancelled = false
            try {
                transfer.synchronize()
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue("stream cancellation propagates", cancelled)
            assertEquals(1, remote.partReads)
            assertFalse(files.resolve(path).exists())
            assertTrue(staging.listFiles()?.isEmpty() == true)
            remote.interrupt = false
            transfer.synchronize()
            assertArrayEquals(body, files.resolve(path).readBytes())
            assertTrue(staging.listFiles()?.isEmpty() == true)
        }
    }
}

private fun SqlDatabase.preference(key: String): String? = query(
    "SELECT value FROM settings WHERE key=?",
    listOf(SqlValue.Text(key)),
) { it.text(0) }.singleOrNull()

private class InterruptedAsset(private val data: ByteArray, hash: String, path: String) : ObjectProvider {
    private val index = "data_governance/blobs_manifest.json"
    private val file = "data_governance/blobs/$path"
    private val manifest = """{"entries":{"$hash":{"relative_path":"$path","size":${data.size},""" +
        """"updated_at":"2026-01-01T00:00:00Z"}},"updated_at":""}"""
    var interrupt = true
    var partReads = 0
        private set

    override suspend fun get(key: String): ByteArray? = when (key) {
        index -> manifest.encodeToByteArray()
        file -> error("must fetch via the streaming port")
        else -> null
    }
    override suspend fun list(prefix: String): List<String> = emptyList()
    override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean): Unit = error("unexpected write")
    override suspend fun delete(key: String): Unit = error("unexpected deletion")

    override suspend fun <T> readStream(key: String, limit: Int, read: (InputStream) -> T): T? {
        if (key != file) return super.readStream(key, limit, read)
        if (!interrupt) return data.inputStream().use(read)
        val part = object : InputStream() {
            override fun read(): Int = error("expected buffered reads")
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (partReads != 0) throw CancellationException("fixture stream cancelled")
                val count = minOf(length, 4096)
                data.copyInto(target, offset, 0, count)
                partReads++
                return count
            }
        }
        return part.use(read)
    }
}
