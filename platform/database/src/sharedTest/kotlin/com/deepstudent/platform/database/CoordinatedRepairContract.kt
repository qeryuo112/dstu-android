package com.deepstudent.platform.database

import com.deepstudent.platform.database.internal.FrozenCatalogLoader
import com.deepstudent.platform.database.internal.MigrationRunner
import com.deepstudent.platform.database.internal.SqliteConnectionFactory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

/** The identical real-bundled-driver repair contract runs on JVM and Android. */
internal class CoordinatedRepairContract(private val root: File, private val source: MigrationSource) {
    private val repair = CoordinatedSchemaRepair.load(source)
    private val catalog = FrozenCatalogLoader.load(source)

    fun freshAndRestartApplyRepair() {
        assertEquals(SharedDatabase.VFS, repair.database)
        assertEquals(20261120, repair.version)
        assertTrue(repair.sql.contains("VALUES ('portable_plugin_records', OLD.id, 'DELETE')"))
        val file = root.resolve("fresh.db")
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            assertEquals(0, db.migrationReport?.fromVersion)
            assertEquals(76, db.migrationReport?.appliedCount)
            assertEquals(repair.version, db.migrationReport?.toVersion)
            assertEquals(repair.after.fingerprint, db.migrationReport?.fingerprint)
            assertEquals(listOf("ok"), db.query("PRAGMA integrity_check") { it.text(0) })
            assertTrue(db.query("PRAGMA foreign_key_check") { it.long(0) }.isEmpty())
            db.execute(insertPlugin("fresh"))
            val id = db.query("SELECT id FROM portable_plugin_records WHERE record_id='fresh'") { it.text(0) }.single()
            db.execute("DELETE FROM portable_plugin_records WHERE record_id='fresh'")
            assertEquals(listOf("INSERT", "DELETE"), db.query(
                "SELECT operation FROM __change_log WHERE table_name='portable_plugin_records' AND record_id=? ORDER BY id",
                listOf(SqlValue.Text(id)),
            ) { it.text(0) })
            assertTrue(db.query("SELECT id FROM portable_plugin_records") { it.text(0) }.isEmpty())
        }
        val before = snapshot(file)
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            assertEquals(20261120, db.migrationReport?.fromVersion)
            assertEquals(0, db.migrationReport?.appliedCount)
            assertEquals(repair.after.fingerprint, db.migrationReport?.fingerprint)
        }
        assertEquals(before, snapshot(file))
    }

    fun oldHeadUpgradePreservesRowsAndLogThenDelete() {
        val file = root.resolve("old-head.db")
        prepareOldHead(file)
        val before = snapshot(file)
        assertEquals(2, before.rows.size)
        assertEquals(3, before.log.size)
        assertEquals(75, before.history.size)
        assertEquals(1, before.governance.size)
        SqliteConnectionFactory().open(file).use { db ->
            assertThrows(Exception::class.java) { db.exec("DELETE FROM portable_plugin_records WHERE record_id='target'") }
        }
        assertEquals(before, snapshot(file)) // The frozen broken trigger must not delete anything.
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            assertEquals(20261117, db.migrationReport?.fromVersion)
            assertEquals(20261120, db.migrationReport?.toVersion)
            assertEquals(1, db.migrationReport?.appliedCount)
            assertEquals(repair.after.fingerprint, db.migrationReport?.fingerprint)
        }
        val upgraded = snapshot(file)
        assertEquals(before.rows, upgraded.rows)
        assertEquals(before.log, upgraded.log)
        assertEquals(before.sequence, upgraded.sequence)
        assertEquals(before.history, upgraded.history.dropLast(1))
        assertEquals(76, upgraded.history.size)
        assertEquals(before.governance, upgraded.governance.take(1))
        assertEquals(2, upgraded.governance.size)
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            assertEquals(0, db.migrationReport?.appliedCount)
        }
        assertEquals(upgraded, snapshot(file))
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            val id = db.query("SELECT id FROM portable_plugin_records WHERE record_id='target'") { it.text(0) }.single()
            db.execute("DELETE FROM portable_plugin_records WHERE record_id='target'")
            assertEquals(listOf("kept-中文"), db.query("SELECT record_id FROM portable_plugin_records") { it.text(0) })
            val last = db.query("SELECT table_name,record_id,operation FROM __change_log ORDER BY id DESC LIMIT 1") {
                listOf(it.text(0), it.text(1), it.text(2))
            }.single()
            assertEquals(listOf("portable_plugin_records", id, "DELETE"), last)
        }
        val deleted = snapshot(file)
        assertEquals(before.log, deleted.log.dropLast(1))
        assertEquals(before.log.size + 1, deleted.log.size)
        assertEquals(upgraded.history, deleted.history)
        assertEquals(upgraded.governance, deleted.governance)
    }

    fun rejectsUntrustedInputsWithoutOpening() {
        val finalPath = "config/schema/final-lock.json"
        val supplementPath = "config/schema/s2-04-repair.json"
        val sqlPath = "config/schema/coordinated/vfs/V20261120__fix_plugin_delete_trigger.sql"
        for ((index, path) in listOf(finalPath, supplementPath, sqlPath).withIndex()) {
            val altered = MigrationSource { requested -> source.read(requested).let { if (requested == path) it + 10 else it } }
            val error = assertThrows(MigrationException::class.java) { CoordinatedSchemaRepair.load(altered) }
            assertEquals(if (path == sqlPath) MigrationDiagnostic.SQL_BYTES_DRIFT else MigrationDiagnostic.SOURCE_INVALID, error.diagnostic)
            val file = root.resolve("bad-$index.db")
            assertThrows(MigrationException::class.java) { DatabaseFactory.openShared(file, SharedDatabase.CHAT_V2, altered) }
            assertFalse(file.exists())
        }
        val secret = "untrusted-source-detail"
        val unreadable = MigrationSource { throw IllegalStateException(secret) }
        val error = assertThrows(MigrationException::class.java) { CoordinatedSchemaRepair.load(unreadable) }
        assertEquals(MigrationDiagnostic.SOURCE_INVALID, error.diagnostic)
        assertFalse(error.toString().contains(secret))
        val absentRepair = MigrationSource { path -> if (path == supplementPath) throw IllegalStateException("missing") else source.read(path) }
        assertEquals(MigrationDiagnostic.SOURCE_INVALID,
            assertThrows(MigrationException::class.java) { CoordinatedSchemaRepair.load(absentRepair) }.diagnostic)
    }

    fun rejectsOldHeadDriftWithoutChangingData() {
        val file = root.resolve("old-head-drift.db")
        prepareOldHead(file)
        SqliteConnectionFactory().open(file).use { it.exec("DROP TRIGGER trg__change_log_portable_plugin_records_delete") }
        val before = snapshot(file)
        val error = assertThrows(MigrationException::class.java) { DatabaseFactory.openShared(file, SharedDatabase.VFS, source) }
        assertEquals(MigrationDiagnostic.SCHEMA_DRIFT, error.diagnostic)
        assertEquals("trg__change_log_portable_plugin_records_delete", error.objectName)
        assertEquals(before, snapshot(file))
    }

    private fun prepareOldHead(file: File) {
        val old = catalog.forDatabase(SharedDatabase.VFS).filter { it.version <= repair.before.version }
        val schema = catalog.schemas.getValue(SharedDatabase.VFS).copy(
            version = repair.before.version, fingerprint = repair.before.fingerprint,
        )
        SqliteConnectionFactory().open(file).use { db ->
            assertEquals(75, MigrationRunner(db, SharedDatabase.VFS, old, schema).run().appliedCount)
            db.exec(insertPlugin("target"))
            db.exec(insertPlugin("kept-中文"))
            db.exec("UPDATE portable_plugin_records SET deleted_at='2026-11-20T02:03:04Z',local_version=10 WHERE record_id='kept-中文'")
            db.exec("UPDATE __change_log SET changed_at='2026-01-01 00:00:00',sync_version=9007199254740993,field_deltas_json='{\"future\":null}'")
        }
    }

    private fun insertPlugin(record: String): String = """
        INSERT INTO portable_plugin_records(
          id,owner_id,owner_kind,namespace,record_id,package_version,source_hash,schema_version,min_writer_schema,
          data_json,unknown_json,deleted_at,device_id,local_version,updated_at
        ) VALUES (
          json_array('plugin.owner','prefs','$record'),'plugin.owner','plugin','prefs','$record','1.2.3',
          printf('%064d',0),2,1,json_object('n',17,'text','值'),json_object('future',json_object('value',null,'hlc','2026-01-01T00:00:00Z','dev','device-1','seq',1)),NULL,'device-1',9,'2026-11-20T01:02:03Z'
        )
    """.trimIndent()

    private fun snapshot(file: File): RepairSnapshot = SqliteConnectionFactory().openReadOnly(file).use { db ->
        val result = RepairSnapshot(
            db.queryTexts("SELECT json_array(id,owner_id,owner_kind,namespace,record_id,package_version,source_hash,schema_version,min_writer_schema,data_json,unknown_json,deleted_at,device_id,local_version,updated_at) FROM portable_plugin_records ORDER BY id"),
            db.queryTexts("SELECT json_array(id,table_name,record_id,operation,changed_at,sync_version,field_deltas_json) FROM __change_log ORDER BY id"),
            db.queryTexts("SELECT json_array(version,name,applied_on,checksum) FROM refinery_schema_history ORDER BY version"),
            db.queryTexts("SELECT json_array(database_id,schema_version,fingerprint,verified_at,canonical_schema) FROM __governance_schema_fingerprints ORDER BY schema_version"),
            db.queryTexts("SELECT json_array(name,seq) FROM sqlite_sequence ORDER BY name"),
        )
        result
    }
}

private data class RepairSnapshot(
    val rows: List<String>, val log: List<String>, val history: List<String>,
    val governance: List<String>, val sequence: List<String>,
)
