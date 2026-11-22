package com.deepstudent.services.sync.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.services.sync.data.transfer.SqlSyncManifest
import com.deepstudent.services.sync.data.transfer.SqlUploadState
import com.deepstudent.services.sync.data.capture.SyncCaptureRegistry
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncManifestSqlTest {
    private val root = Files.createTempDirectory("sync-manifest-sql-").toFile()
    private val repository = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "config/schema/final-lock.json").isFile }
    private val migrations = MigrationSource { repository.resolve(it).readBytes() }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun checksumRegistryMatchesFrozenRowSyncAndCoordinatedAdditions() {
        val registry = JsonValue.parseObject(repository.resolve("config/sync-registry-contract.json").readText())
        val rows = (registry["registry"] as JsonValue.Array).values.map { it as JsonValue.Object }
        val expected = rows.filter { text(it["database"]) == "vfs" && text(it["category"]) == "RowSync" }.associate {
            text(it["table"]) to text((it["logicalPk"] as JsonValue.Object)["declared"])
        } + mapOf("portable_media_events" to "event_id", "portable_plugin_records" to "id")
        assertEquals(expected, SyncCaptureRegistry.load(migrations).checksumKeys(SharedDatabase.VFS))
    }

    @Test fun realMigratedSnapshotHasExactSchemaAndChangesChecksumWithoutTouchingSourceData() {
        DatabaseFactory.openShared(root.resolve("vfs.db"), SharedDatabase.VFS, migrations).use { database ->
            val journal = SqlUploadState(database, "instance", "device")
            val manifest = SqlSyncManifest(database, "instance", "device", journal, SyncCaptureRegistry.load(migrations).checksumKeys(SharedDatabase.VFS))
            val before = manifest.snapshot()
            val initial = ((before["databases"] as JsonValue.Object)["vfs"] as JsonValue.Object)
            assertEquals(JsonValue.integer(20261120), initial["schema_version"])
            assertTrue(text(initial["checksum"]).matches(Regex("[a-f0-9]{32}")))
            database.execute("INSERT INTO resources(id,hash,type,storage_mode,data,created_at,updated_at) VALUES('public-r','public-h','note','inline','public',1,1)")
            val changed = ((manifest.snapshot()["databases"] as JsonValue.Object)["vfs"] as JsonValue.Object)
            assertTrue(initial["checksum"] != changed["checksum"])
            assertEquals("public", database.query("SELECT data FROM resources WHERE id='public-r'") { it.text(0) }.single())
        }
    }

    private fun text(value: JsonValue?): String = (value as JsonValue.Text).value
}
