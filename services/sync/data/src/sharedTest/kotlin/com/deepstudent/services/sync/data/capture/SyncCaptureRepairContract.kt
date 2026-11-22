package com.deepstudent.services.sync.data.capture

import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import org.junit.Assert.*

/** Real-driver repair tests shared by the host JVM and Android instrumentation. */
internal class SyncCaptureRepairContract(private val root: java.io.File, private val source: MigrationSource) {
    private val registry = SyncCaptureRegistry.load(source)

    fun pluginCrudRecordsTheSameLogicalKeyThroughTombstoneAndDelete() {
        open(SharedDatabase.VFS).use { db ->
            insertPlugin(db)
            db.execute("UPDATE portable_plugin_records SET data_json='{\"a\":1}' WHERE owner_id='plugin.p'")
            db.execute("UPDATE portable_plugin_records SET deleted_at='2026-01-02T00:00:00Z', local_version=2 WHERE owner_id='plugin.p'")
            assertEquals("2026-01-02T00:00:00Z", db.query(
                "SELECT deleted_at FROM portable_plugin_records WHERE id=?", listOf(SqlValue.Text(PLUGIN_ID)),
            ) { it.text(0) }.single())
            val beforePreflight = pluginChanges(db)
            assertEquals(listOf("INSERT", "UPDATE", "UPDATE").map { it to PLUGIN_ID }, beforePreflight)
            assertSameReportValid(db)
            assertEquals(beforePreflight, pluginChanges(db))
            db.execute("DELETE FROM portable_plugin_records WHERE id=?", listOf(SqlValue.Text(PLUGIN_ID)))
            assertEquals(listOf("INSERT", "UPDATE", "UPDATE", "DELETE").map { it to PLUGIN_ID }, pluginChanges(db))
            assertEquals(0L, db.query("SELECT count(*) FROM portable_plugin_records WHERE id=?",
                listOf(SqlValue.Text(PLUGIN_ID))) { it.long(0) }.single())
        }
    }

    fun repairedTriggerHashMatchesStoredSqlAndOldFrozenTriggerDrifts() {
        open(SharedDatabase.VFS).use { db ->
            val expected = registry.find(SharedDatabase.VFS, PLUGIN_TABLE)!!.triggers.single { it.name == PLUGIN_TRIGGER }
            assertEquals(CaptureOperation.DELETE, expected.operation)
            assertTrue(expected.captures)
            assertEquals(expected.sha256, sha256(pluginTriggerSql(db).toByteArray(Charsets.UTF_8)))
            assertSameReportValid(db)

            val frozenSql = source.read(PLUGIN_FROZEN_MIGRATION).toString(Charsets.UTF_8)
            val frozen = CoordinatedTriggerParser.parse(frozenSql).single { it.second.name == PLUGIN_TRIGGER }.second
            val definition = checkNotNull(Regex(
                "(?ims)^CREATE\\s+TRIGGER\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?$PLUGIN_TRIGGER\\s*\\n.*?^END;",
            ).find(frozenSql)).value
            assertNotEquals(expected.sha256, frozen.sha256)
            db.execute("DROP TRIGGER $PLUGIN_TRIGGER")
            db.execute(definition)
            // Compare the parsed frozen source against SQLite's actual normalized stored SQL as well.
            assertEquals(frozen.sha256, sha256(pluginTriggerSql(db).toByteArray(Charsets.UTF_8)))
            val report = SyncCapturePreflight.validate(SharedDatabase.VFS, db, registry)
            val drift = report.issues.single()
            assertEquals(CaptureDiagnostic.TRIGGER_DRIFT, drift.diagnostic)
            assertEquals(PLUGIN_TABLE, drift.table)
            assertEquals(PLUGIN_TRIGGER, drift.trigger)
            assertEquals(CaptureOperation.DELETE, drift.operation)
            assertEquals(0, report.operationChecks)
            assertEquals(CaptureDiagnostic.TRIGGER_DRIFT,
                assertThrows(SyncCaptureException::class.java) { report.requireValid() }.diagnostic)
        }
    }

    fun injectedBadDeleteIsDiagnosedWithoutLosingRowOrLog() {
        open(SharedDatabase.VFS).use { db ->
            insertPlugin(db)
            db.execute("UPDATE portable_plugin_records SET deleted_at='2026-01-02T00:00:00Z' WHERE id=?",
                listOf(SqlValue.Text(PLUGIN_ID)))
            val beforeChanges = pluginChanges(db)
            val beforeRows = pluginRows(db)
            val beforeLogCount = db.query("SELECT count(*) FROM __change_log") { it.long(0) }.single()
            assertEquals(listOf("INSERT", "UPDATE").map { it to PLUGIN_ID }, beforeChanges)
            assertEquals(1, beforeRows.size)

            val goodSql = pluginTriggerSql(db)
            val badSql = goodSql.replace("OLD.id", "NEW.id")
            assertNotEquals(goodSql, badSql)
            db.execute("DROP TRIGGER $PLUGIN_TRIGGER")
            db.execute(badSql)
            val strict = SyncCapturePreflight.validate(SharedDatabase.VFS, db, registry)
            assertEquals(CaptureDiagnostic.TRIGGER_DRIFT, strict.issues.single().diagnostic)
            assertEquals(0, strict.operationChecks)
            // Deliberately change only the test registry's expected hash so operation inspection
            // reaches SQLite's compile error. The real registry would reject this as hash drift.
            val deliberatelyBadRegistry = registryWithExpectedPluginTrigger(pluginTriggerSql(db))
            val report = SyncCapturePreflight.validate(SharedDatabase.VFS, db, deliberatelyBadRegistry)
            assertPluginFailure(report)
            assertEquals(report.operationChecks - 1, report.operationSucceeded)
            val notes = setOf("resources", "notes", "folders", "folder_items", "files", "blobs",
                "note_document_formats", "note_document_revisions", "note_learning_relations")
            assertSame(report, report.requireReady(notes))
            val scoped = assertThrows(SyncCaptureException::class.java) { report.requireReady(setOf(PLUGIN_TABLE)) }
            assertEquals(CaptureDiagnostic.OPERATION_COMPILE_FAILED, scoped.diagnostic)
            assertEquals(PLUGIN_TRIGGER, scoped.trigger)
            assertThrows(Exception::class.java) {
                db.execute("DELETE FROM portable_plugin_records WHERE id=?", listOf(SqlValue.Text(PLUGIN_ID)))
            }
            assertEquals(beforeRows, pluginRows(db))
            assertEquals(beforeChanges, pluginChanges(db))
            assertEquals(beforeLogCount, db.query("SELECT count(*) FROM __change_log") { it.long(0) }.single())
        }
    }

    private fun assertPluginFailure(report: CaptureReport) {
        assertFalse(report.valid)
        assertTrue(report.structureIssues.isEmpty())
        val issue = report.operationIssues.single()
        assertEquals(CaptureDiagnostic.OPERATION_COMPILE_FAILED, issue.diagnostic)
        assertEquals(SharedDatabase.VFS, issue.database)
        assertEquals("portable_plugin_records", issue.table)
        assertEquals("trg__change_log_portable_plugin_records_delete", issue.trigger)
        assertEquals(CaptureOperation.DELETE, issue.operation)
        val failure = assertThrows(SyncCaptureException::class.java) { report.requireValid() }
        assertEquals(CaptureDiagnostic.OPERATION_COMPILE_FAILED, failure.diagnostic)
        assertEquals(issue.trigger, failure.trigger)
    }

    private fun assertSameReportValid(db: SqlDatabase) {
        val report = SyncCapturePreflight.validate(SharedDatabase.VFS, db, registry)
        assertTrue("${report.issues}", report.valid)
        assertSame(report, report.requireValid())
    }

    private fun registryWithExpectedPluginTrigger(storedSql: String): SyncCaptureRegistry {
        val original = registry.find(SharedDatabase.VFS, PLUGIN_TABLE)!!
        val updated = SyncCaptureEntry(original.database, original.table, original.category, original.conflictPolicy,
            original.logicalPrimaryKey, original.businessUniqueKeys, original.schema.copy(triggers = original.triggers.map {
                if (it.name == PLUGIN_TRIGGER) it.copy(sha256 = sha256(storedSql.toByteArray(Charsets.UTF_8))) else it
            }))
        return SyncCaptureRegistry(registry.entries.map { if (it === original) updated else it },
            registry.versions, registry.changeLogSchemas)
    }

    private fun insertPlugin(db: SqlDatabase) = db.execute(
        "INSERT INTO portable_plugin_records(id,owner_id,owner_kind,namespace,record_id,package_version,source_hash,schema_version,data_json,device_id,updated_at) " +
            "VALUES(json_array('plugin.p','n','r'),'plugin.p','plugin','n','r','1','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',1,'{}','dev','2026-01-01T00:00:00Z')",
    )

    private fun pluginChanges(db: SqlDatabase): List<Pair<String, String>> = db.query(
        "SELECT operation,record_id FROM __change_log WHERE table_name=? ORDER BY id", listOf(SqlValue.Text(PLUGIN_TABLE)),
    ) { it.text(0) to it.text(1) }

    private fun pluginRows(db: SqlDatabase): List<List<String>> = db.query(
        "SELECT id,data_json,deleted_at FROM portable_plugin_records WHERE id=?", listOf(SqlValue.Text(PLUGIN_ID)),
    ) { listOf(it.text(0), it.text(1), it.text(2)) }

    private fun pluginTriggerSql(db: SqlDatabase): String = db.query(
        "SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?", listOf(SqlValue.Text(PLUGIN_TRIGGER)),
    ) { it.text(0) }.single()

    private fun open(database: SharedDatabase): SqlDatabase = DatabaseFactory.openShared(root.resolve("${database.id}.db"), database, source)

    private companion object {
        const val PLUGIN_TABLE = "portable_plugin_records"
        const val PLUGIN_TRIGGER = "trg__change_log_portable_plugin_records_delete"
        const val PLUGIN_FROZEN_MIGRATION = "config/schema/coordinated/vfs/V20261117__plugin_skill_data_contract.sql"
        const val PLUGIN_ID = "[\"plugin.p\",\"n\",\"r\"]"
    }
}
