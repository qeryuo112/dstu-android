package com.deepstudent.services.sync.data.s230

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.services.sync.data.apply.ImmutableMediaEventPolicy
import com.deepstudent.services.sync.data.apply.IncomingDomain
import com.deepstudent.services.sync.data.apply.PluginDataPolicy
import com.deepstudent.services.sync.data.apply.RegisteredIncomingRows
import com.deepstudent.services.sync.data.apply.RegisteredSqlRowApplier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class S230PolicyTest {
    private val root = Files.createTempDirectory("s230-policy-").toFile()

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun mediaIsImmutableEvenIfChangedPayloadIsOlderAndDeleteIsRejected() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            val db = f.db(SharedDatabase.VFS)
            val registration = RegisteredIncomingRows(
                SharedDatabase.VFS,
                f.registry,
                listOf(IncomingDomain(SharedDatabase.VFS, "media", listOf("portable_media_events"))),
                mapOf("portable_media_events" to ImmutableMediaEventPolicy),
            )
            val applier = RegisteredSqlRowApplier(registration)
            val first = media(5, "2026-01-02T00:00:00Z")
            db.transaction { applier.apply(this, listOf(first)) }
            val changed = media(6, "2026-01-01T00:00:00Z")
            assertThrows(IllegalArgumentException::class.java) { db.transaction { applier.apply(this, listOf(changed)) } }
            val deleted = JsonValue.Object.Builder().put("database_name", "vfs").put("table_name", "portable_media_events")
                .put("record_id", "event").put("operation", "Delete").put("changed_at", "2026-01-01T00:00:00Z")
                .putNull("data").build().literal().toByteArray()
            assertThrows(IllegalArgumentException::class.java) { applier.validate(listOf(deleted)) }
            assertEquals(5L, db.query("SELECT seconds FROM portable_media_events WHERE event_id='event'") { it.long(0) }.single())
        }
    }

    @Test fun pluginIdentityDowngradeAndInvalidUnknownEnvelopeAreRejected() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            val db = f.db(SharedDatabase.VFS)
            val registration = RegisteredIncomingRows(
                SharedDatabase.VFS,
                f.registry,
                listOf(IncomingDomain(SharedDatabase.VFS, "plugins", listOf("portable_plugin_records"))),
                mapOf("portable_plugin_records" to PluginDataPolicy),
            )
            val applier = RegisteredSqlRowApplier(registration)
            db.transaction { applier.apply(this, listOf(plugin(2, "{}", "{}"))) }
            assertThrows(IllegalArgumentException::class.java) { db.transaction { applier.apply(this, listOf(plugin(1, "{}", "{}"))) } }
            assertThrows(IllegalStateException::class.java) { applier.validate(listOf(plugin(2, "{}", "{\"bad\":42}"))) }
            assertThrows(IllegalArgumentException::class.java) { applier.validate(listOf(plugin(2, "{}", "{}", "plugin.other"))) }
            assertEquals(2L, db.query("SELECT schema_version FROM portable_plugin_records") { it.long(0) }.single())
        }
    }

    private fun media(seconds: Int, updated: String) = s230Change(
        "vfs",
        "portable_media_events",
        "event",
        """{"event_id":"event","device_id":"device","kind":"study","resource_id":"resource","logical_date":"2026-01-01",""" +
            """"seconds":$seconds,"observed_at":"2026-01-01T00:00:00Z","data":"{}","local_version":1,""" +
            """"updated_at":"$updated","deleted_at":null}""",
    )
    private fun plugin(version: Int, payload: String, unknown: String, owner: String = "plugin.p"): ByteArray {
        val id = """["plugin.p","n","r"]"""
        val data = JsonValue.Object.Builder().put("id", id).put("owner_id", owner).put("owner_kind", "plugin")
            .put("namespace", "n").put("record_id", "r").put("package_version", "1").put("source_hash", "a".repeat(64))
            .put("schema_version", version).put("min_writer_schema", 1).put("data_json", payload).put("unknown_json", unknown)
            .put("device_id", "device").put("local_version", 1).put("updated_at", "2026-01-02T00:00:00Z").build()
        return s230Change("vfs", "portable_plugin_records", id, data.literal())
    }
}
