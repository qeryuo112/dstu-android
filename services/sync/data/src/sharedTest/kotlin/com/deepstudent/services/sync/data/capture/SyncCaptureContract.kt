package com.deepstudent.services.sync.data.capture

import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.platform.database.SqlValue
import org.junit.Assert.*

internal class SyncCaptureContract(private val root: java.io.File, private val source: MigrationSource) {
    private val registry = SyncCaptureRegistry.load(source)

    fun frozenRegistryAndAllFourRealSchemasAgree() {
        assertEquals(126, registry.entries.size)
        assertEquals(61, registry.entries.count { it.category == SyncCategory.RowSync })
        assertEquals(listOf("session_id", "tag"), registry.find(SharedDatabase.CHAT_V2, "chat_v2_session_tags")!!.logicalPrimaryKey)
        val revisions = registry.find(SharedDatabase.VFS, "note_document_revisions")!!
        assertEquals(listOf("version_id"), revisions.logicalPrimaryKey)
        assertEquals(listOf("seq"), revisions.sqlitePrimaryKey)
        assertEquals(setOf(CaptureOperation.INSERT, CaptureOperation.UPDATE),
            revisions.triggers.filter { it.captures }.map { it.operation }.toSet())
        assertEquals(SyncCategory.RowSync, registry.find(SharedDatabase.MISTAKES, "custom_anki_templates")!!.category)
        assertEquals("Lww", registry.find(SharedDatabase.MISTAKES, "custom_anki_templates")!!.conflictPolicy)
        assertEquals("event_id", registry.checksumKeys(SharedDatabase.VFS)["portable_media_events"])
        assertEquals("id", registry.checksumKeys(SharedDatabase.VFS)["portable_plugin_records"])
        var attempted = 0
        var succeeded = 0
        SharedDatabase.entries.forEach { owner ->
            open(owner).use { db ->
                val report = SyncCapturePreflight.validate(owner, db, registry)
                assertEquals(registry.entries.count { it.database == owner }, report.entriesChecked)
                assertEquals(report.rowSyncChecked * 3, report.operationChecks)
                assertEquals(report.operationChecks, report.operationSucceeded)
                assertTrue("$owner ${report.issues}", report.valid)
                assertSame(report, report.requireValid())
                assertEquals(registry.schemaVersions().getValue(owner.id), db.migrationReport!!.toVersion)
                attempted += report.operationChecks
                succeeded += report.operationSucceeded
            }
        }
        assertEquals(183, attempted)
        assertEquals(183, succeeded)
    }

    fun effectiveSchemaVersionsAreDefensiveAndUnmodifiable() {
        val expected = mapOf("vfs" to 20261120, "chat_v2" to 20261111,
            "mistakes" to 20261006, "llm_usage" to 20260826)
        val versions = registry.schemaVersions()
        assertEquals(expected, versions)
        assertNotSame(versions, registry.schemaVersions())
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (versions as MutableMap<String, Int>)["vfs"] = 1
        }
        assertEquals(expected, versions)
        assertEquals(expected, registry.schemaVersions())
    }

    fun rowSyncCrudAndLocalCrudAreIsolatedInRealDatabases() {
        open(SharedDatabase.VFS).use { db ->
            db.execute("INSERT INTO resources(id,hash,type,created_at,updated_at) VALUES('r','h','note',1,1)")
            db.execute("UPDATE resources SET updated_at=2 WHERE id='r'")
            db.execute("DELETE FROM resources WHERE id='r'")
            assertEquals(listOf("INSERT", "UPDATE", "DELETE"), operations(db, "resources", "r"))
            val count = count(db)
            db.execute("INSERT INTO qbank_practice_drafts(id,scope_id,document_json,created_at,updated_at) VALUES('d','s','{}','a','a')")
            db.execute("UPDATE qbank_practice_drafts SET updated_at='b' WHERE id='d'")
            db.execute("DELETE FROM qbank_practice_drafts WHERE id='d'")
            assertEquals(count, count(db))
        }
        open(SharedDatabase.CHAT_V2).use { db ->
            db.execute("INSERT INTO chat_v2_sessions(id,mode,created_at,updated_at) VALUES('s','general_chat','a','a')")
            db.execute("INSERT INTO chat_v2_session_tags(session_id,tag) VALUES('s','tag')")
            db.execute("UPDATE chat_v2_session_tags SET tag_type='manual' WHERE session_id='s' AND tag='tag'")
            db.execute("DELETE FROM chat_v2_session_tags WHERE session_id='s' AND tag='tag'")
            val records = db.query("SELECT operation,record_id FROM __change_log WHERE table_name='chat_v2_session_tags' ORDER BY id") { it.text(0) to it.text(1) }
            assertEquals(listOf("INSERT", "UPDATE", "DELETE"), records.map { it.first })
            records.forEach { assertTrue(it.second.contains("s") && it.second.contains("tag")) }
            val count = count(db)
            db.execute("INSERT INTO revocation_epochs(kind,task_id,epoch,updated_at) VALUES('task','t',1,'a')")
            db.execute("UPDATE revocation_epochs SET epoch=2 WHERE kind='task' AND task_id='t'")
            db.execute("DELETE FROM revocation_epochs WHERE kind='task' AND task_id='t'")
            assertEquals(count, count(db))
        }
        open(SharedDatabase.MISTAKES).use { db ->
            db.execute("INSERT INTO custom_anki_templates(id,name,preview_front,preview_back,generation_prompt,front_template,back_template,css_style) VALUES('t','n','f','b','p','f','b','c')")
            db.execute("UPDATE custom_anki_templates SET name='n2' WHERE id='t'")
            db.execute("DELETE FROM custom_anki_templates WHERE id='t'")
            assertEquals(listOf("INSERT", "UPDATE", "DELETE"), operations(db, "custom_anki_templates", "t"))
            val beforeBuiltIn = count(db)
            db.execute("INSERT INTO custom_anki_templates(id,name,preview_front,preview_back,generation_prompt,front_template,back_template,css_style,is_built_in) VALUES('built','builtin','f','b','p','f','b','c',1)")
            assertEquals(beforeBuiltIn, count(db))
            db.execute("UPDATE custom_anki_templates SET user_modified=1 WHERE id='built'")
            db.execute("DELETE FROM custom_anki_templates WHERE id='built'")
            assertEquals(listOf("UPDATE", "DELETE"), operations(db, "custom_anki_templates", "built"))
            val count = count(db)
            db.execute("INSERT INTO migration_progress(category,status) VALUES('test','running')")
            db.execute("UPDATE migration_progress SET status='done' WHERE category='test'")
            db.execute("DELETE FROM migration_progress WHERE category='test'")
            assertEquals(count, count(db))
        }
        open(SharedDatabase.LLM_USAGE).use { db ->
            db.execute("INSERT INTO llm_usage_logs(id,timestamp,provider,model,caller_type) VALUES('l','2026-01-01T00:00:00Z','p','m','other')")
            db.execute("UPDATE llm_usage_logs SET total_tokens=1 WHERE id='l'")
            db.execute("DELETE FROM llm_usage_logs WHERE id='l'")
            assertEquals(listOf("INSERT", "UPDATE", "DELETE"), operations(db, "llm_usage_logs", "l"))
            val count = count(db)
            db.execute("INSERT INTO llm_usage_daily(date,caller_type,model,provider) VALUES('2026-01-01','other','m','p')")
            db.execute("UPDATE llm_usage_daily SET request_count=1 WHERE date='2026-01-01'")
            db.execute("DELETE FROM llm_usage_daily WHERE date='2026-01-01'")
            assertEquals(count, count(db))
        }
    }

    fun coordinatedEntitiesPreserveObservedCaptureSemantics() {
        open(SharedDatabase.VFS).use { db ->
            db.execute("INSERT INTO portable_media_events(event_id,device_id,kind,resource_id,logical_date,seconds,observed_at,local_version,updated_at) VALUES('event','dev','study','res','2026-01-01',5,'2026-01-01T00:00:00Z',1,'2026-01-01T00:00:00Z')")
            db.execute("UPDATE portable_media_events SET local_version=2 WHERE event_id='event'")
            assertThrows(Exception::class.java) { db.execute("DELETE FROM portable_media_events WHERE event_id='event'") }
            assertEquals(listOf("INSERT", "UPDATE"), operations(db, "portable_media_events", "event"))
            assertEquals(1L, db.query("SELECT count(*) FROM portable_media_events WHERE event_id='event'") { it.long(0) }.single())
        }
    }

    fun missingAndAlteredCaptureAreLocatedWithoutValues() {
        open(SharedDatabase.VFS).use { db ->
            db.execute("DROP TRIGGER trg__change_log_resources_delete")
            val missingReport = SyncCapturePreflight.validate(SharedDatabase.VFS, db, registry)
            val missing = missingReport.issues.single { it.diagnostic == CaptureDiagnostic.TRIGGER_MISSING }
            assertEquals(CaptureDiagnostic.TRIGGER_MISSING,
                assertThrows(SyncCaptureException::class.java) { missingReport.requireReady(setOf("notes")) }.diagnostic)
            assertEquals(0, missingReport.operationChecks)
            assertEquals("resources", missing.table)
            assertEquals("trg__change_log_resources_delete", missing.trigger)
            assertEquals(CaptureOperation.DELETE, missing.operation)
            db.execute("CREATE TRIGGER trg__change_log_resources_delete AFTER DELETE ON resources BEGIN SELECT 1; END")
            val drift = SyncCapturePreflight.validate(SharedDatabase.VFS, db, registry).issues.single { it.diagnostic == CaptureDiagnostic.TRIGGER_DRIFT }
            assertEquals("resources", drift.table)
            assertEquals(CaptureOperation.DELETE, drift.operation)
            val failure = try { SyncCapturePreflight.validate(SharedDatabase.VFS, db, registry).requireValid(); null } catch (e: SyncCaptureException) { e }
            assertNotNull(failure)
            assertFalse(failure.toString().contains(root.absolutePath))
        }
    }

    fun extraCaptureAndCompetingTriggerAreRejectedWithoutLeakingUnknownName() {
        open(SharedDatabase.MISTAKES).use { db ->
            db.execute("CREATE TRIGGER private_name AFTER INSERT ON migration_progress BEGIN INSERT INTO __change_log(table_name,record_id,operation) VALUES('private','secret','INSERT'); END")
            val issue = SyncCapturePreflight.validate(SharedDatabase.MISTAKES, db, registry).issues.single { it.diagnostic == CaptureDiagnostic.TRIGGER_UNEXPECTED }
            assertEquals("migration_progress", issue.table)
            assertNull(issue.trigger)
            assertEquals(CaptureOperation.INSERT, issue.operation)
            assertTrue(issue.objectHash!!.matches(Regex("[a-f0-9]{64}")))
            assertFalse(issue.toString().contains("private"))
        }
    }

    fun changedChangeLogDefinitionFailsBeforeSynchronization() {
        open(SharedDatabase.VFS).use { db ->
            db.execute("DROP TABLE __change_log")
            db.execute("CREATE TABLE __change_log(id INTEGER PRIMARY KEY, table_name TEXT, record_id TEXT, operation TEXT, changed_at TEXT, sync_version INTEGER, field_deltas_json TEXT)")
            val report = SyncCapturePreflight.validate(SharedDatabase.VFS, db, registry)
            val issue = report.issues.single { it.diagnostic == CaptureDiagnostic.CHANGE_LOG_SCHEMA }
            assertEquals(SharedDatabase.VFS, issue.database)
            assertEquals("__change_log", issue.table)
            assertNull(issue.trigger)
        }
    }

    fun unknownTriggerOnChangeLogFailsWithoutLeakingName() {
        open(SharedDatabase.LLM_USAGE).use { db ->
            db.execute("CREATE TRIGGER private_log AFTER INSERT ON __change_log BEGIN DELETE FROM __change_log; END")
            val issue = SyncCapturePreflight.validate(SharedDatabase.LLM_USAGE, db, registry).issues
                .single { it.diagnostic == CaptureDiagnostic.TRIGGER_UNEXPECTED }
            assertEquals("__change_log", issue.table)
            assertNull(issue.trigger)
            assertEquals(CaptureOperation.INSERT, issue.operation)
            assertTrue(issue.objectHash!!.matches(Regex("[a-f0-9]{64}")))
        }
    }

    fun wrongCompositePrimaryKeyIsLocated() {
        open(SharedDatabase.MISTAKES).use { db ->
            db.execute("DROP TABLE migration_progress")
            db.execute("CREATE TABLE migration_progress(category TEXT, status TEXT, PRIMARY KEY(category,status))")
            val issue = SyncCapturePreflight.validate(SharedDatabase.MISTAKES, db, registry).issues
                .single { it.diagnostic == CaptureDiagnostic.PRIMARY_KEY_DRIFT }
            assertEquals("migration_progress", issue.table)
            assertEquals(SharedDatabase.MISTAKES, issue.database)
        }
    }

    fun scopeAllowsOnlyRegisteredConsumption() {
        open(SharedDatabase.VFS).use { db ->
            val report = SyncCapturePreflight.validate(SharedDatabase.VFS, db, registry)
            assertSame(report, report.requireValid())
            val notes = setOf("resources", "notes", "folders", "folder_items", "files", "blobs",
                "note_document_formats", "note_document_revisions", "note_learning_relations")
            assertSame(report, report.requireReady(notes))
            assertSame(report, report.requireReady(setOf("portable_plugin_records")))
            for (scope in listOf(emptySet(), setOf("migration_progress"), setOf("private_user_name"))) {
                val rejected = assertThrows(SyncCaptureException::class.java) { report.requireReady(scope) }
                assertEquals(CaptureDiagnostic.SCOPE_INVALID, rejected.diagnostic)
                assertNull(rejected.table)
                assertFalse(rejected.toString().contains("private_user_name"))
            }
        }
        open(SharedDatabase.MISTAKES).use { db ->
            val report = SyncCapturePreflight.validate(SharedDatabase.MISTAKES, db, registry)
            assertTrue(report.valid)
            assertSame(report, report.requireReady(setOf("anki_decks")))
            val rejected = assertThrows(SyncCaptureException::class.java) { report.requireReady(setOf("migration_progress")) }
            assertEquals(CaptureDiagnostic.SCOPE_INVALID, rejected.diagnostic)
        }
    }

    fun unexpectedReadFailureBlocksEveryConsumptionScope() {
        open(SharedDatabase.VFS).use { db ->
            val broken = object : SqlDatabase by db {
                override fun <T> readSnapshot(block: SqlReadOperations.() -> T): T = db.readSnapshot {
                    val reader = this
                    val failing = object : SqlReadOperations {
                        override fun <R> query(sql: String, args: List<SqlValue>, map: (SqlRow) -> R): List<R> {
                            if (sql.startsWith("EXPLAIN QUERY PLAN")) throw IllegalStateException("private failure /tmp/private")
                            return reader.query(sql, args, map)
                        }
                    }
                    failing.block()
                }
            }
            val report = SyncCapturePreflight.validate(SharedDatabase.VFS, broken, registry)
            assertEquals(CaptureDiagnostic.QUERY_FAILED, report.structureIssues.single().diagnostic)
            assertTrue(report.operationIssues.isEmpty())
            val failure = assertThrows(SyncCaptureException::class.java) { report.requireReady(setOf("notes")) }
            assertEquals(CaptureDiagnostic.QUERY_FAILED, failure.diagnostic)
            assertFalse(failure.toString().contains("private"))
        }
    }

    fun sourceBytesAreCheckedWithoutLeakingSuppliedDetails() {
        val changed = MigrationSource { path -> source.read(path).let { if (path.endsWith("sync-registry-contract.json")) it + 32 else it } }
        val thrown = try { SyncCaptureRegistry.load(changed); null } catch (e: SyncCaptureSourceException) { e }
        assertEquals(CaptureSourceDiagnostic.BYTES_DRIFT, thrown?.diagnostic)
        for (target in listOf("config/schema/s2-04-repair.json",
            "config/schema/coordinated/vfs/V20261120__fix_plugin_delete_trigger.sql")) {
            val tampered = MigrationSource { path -> source.read(path).let { if (path == target) it + 32 else it } }
            val failure = assertThrows(SyncCaptureSourceException::class.java) { SyncCaptureRegistry.load(tampered) }
            assertEquals(if (target.endsWith(".sql")) CaptureSourceDiagnostic.BYTES_DRIFT else CaptureSourceDiagnostic.CONTRACT_INVALID,
                failure.diagnostic)
            assertFalse(failure.toString().contains(target))
            val absent = MigrationSource { path -> if (path == target) error("private source failure") else source.read(path) }
            val unavailable = assertThrows(SyncCaptureSourceException::class.java) { SyncCaptureRegistry.load(absent) }
            assertEquals(CaptureSourceDiagnostic.UNAVAILABLE, unavailable.diagnostic)
            assertFalse(unavailable.toString().contains("private"))
        }
    }

    fun callerCannotMisidentifyARealSharedDatabase() {
        open(SharedDatabase.VFS).use { db ->
            val issue = SyncCapturePreflight.validate(SharedDatabase.MISTAKES, db, registry).issues.single()
            assertEquals(CaptureDiagnostic.DATABASE_IDENTITY, issue.diagnostic)
        }
    }

    private fun open(database: SharedDatabase): SqlDatabase = DatabaseFactory.openShared(root.resolve("${database.id}.db"), database, source)

    private fun count(db: SqlDatabase): Long = db.query("SELECT count(*) FROM __change_log") { it.long(0) }.single()
    private fun operations(db: SqlDatabase, table: String, id: String): List<String> = db.query(
        "SELECT operation FROM __change_log WHERE table_name=? AND record_id=? ORDER BY id", listOf(SqlValue.Text(table), SqlValue.Text(id)),
    ) { it.text(0) }
}
