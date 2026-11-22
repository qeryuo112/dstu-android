package com.deepstudent.platform.database

import com.deepstudent.platform.database.internal.FrozenCatalogLoader
import com.deepstudent.platform.database.internal.FrozenMigration
import com.deepstudent.platform.database.internal.FrozenSchema
import com.deepstudent.platform.database.internal.MigrationRunner
import com.deepstudent.platform.database.internal.SchemaFingerprint
import com.deepstudent.platform.database.internal.SchemaScope
import com.deepstudent.platform.database.internal.SqliteConnectionFactory
import com.deepstudent.platform.database.internal.SqliteSession
import com.deepstudent.platform.database.internal.schemaScope
import com.deepstudent.platform.database.internal.splitStatements
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

/** Shared assertions, backed by the actual bundled SQLite JNI on both platforms. */
internal class MigrationS202Contract(private val root: File, private val source: MigrationSource, fixture: ByteArray) {
    private val upgrade = Json.parseToJsonElement(fixture.decodeToString()).jsonObject
    private val lock = Json.parseToJsonElement(source.read("config/schema/final-lock.json").decodeToString()).jsonObject
    private val catalog = FrozenCatalogLoader.load(source)

    fun freshAndRestartPreserveGovernanceAndHistory() {
        for (database in SharedDatabase.entries) {
            val golden = lock.getValue("databases").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == database.id }.jsonObject
            val expectedCount = golden.getValue("migrationCount").jsonPrimitive.content.toInt() +
                if (database == SharedDatabase.VFS) 1 else 0
            val expectedFingerprint = if (database == SharedDatabase.VFS) {
                CoordinatedSchemaRepair.load(source).after.fingerprint
            } else golden.getValue("fingerprint").jsonPrimitive.content
            val file = root.resolve("fresh-${database.id}.db")
            val events = mutableListOf<MigrationLogEvent>()
            DatabaseFactory.openShared(file, database, source) { events += it }.use { db ->
                assertEquals(expectedCount, db.migrationReport?.appliedCount)
                assertEquals(expectedFingerprint, db.migrationReport?.fingerprint)
                assertEquals(listOf("ok"), db.query("PRAGMA integrity_check") { it.text(0) })
                assertTrue(db.query("PRAGMA foreign_key_check") { it.long(0) }.isEmpty())
            }
            assertEquals(0, events.single().fromVersion)
            assertEquals(expectedCount, events.single().appliedCount)
            assertEquals(expectedFingerprint, events.single().fingerprint)
            SqliteConnectionFactory().open(file).use { observer ->
                val history = history(observer)
                val governance = governance(observer)
                val before = observer.queryLong("PRAGMA data_version")
                DatabaseFactory.openShared(file, database, source).use { db ->
                    assertEquals(0, db.migrationReport?.appliedCount)
                    // The live-owner path must also remain read-only.
                    DatabaseFactory.openShared(file, database, source).use { other ->
                        assertEquals(0, other.migrationReport?.appliedCount)
                    }
                }
                assertEquals(before, observer.queryLong("PRAGMA data_version"))
                assertEquals(history, history(observer))
                assertEquals(governance, governance(observer))
            }
        }
    }

    fun vfs20261111UpgradeMatchesFrozenFixture() {
        val file = root.resolve("vfs-20261111.db")
        val old = catalog.forDatabase(SharedDatabase.VFS).filter { it.version <= 20261111 }
        val before = upgrade.getValue("values").jsonObject.getValue("before").jsonObject
        SqliteConnectionFactory().open(file).use { session ->
            session.exec("PRAGMA foreign_keys=ON")
            // Build only the disposable input in one commit; DatabaseFactory performs the upgrade below.
            session.exec("BEGIN IMMEDIATE")
            session.exec("CREATE TABLE refinery_schema_history(version int4 PRIMARY KEY,name VARCHAR(255),applied_on VARCHAR(255),checksum VARCHAR(255))")
            for (migration in old) {
                splitStatements(migration.sql).forEach(session::exec)
                session.execBind("INSERT INTO refinery_schema_history VALUES(?,?,?,?)") {
                    it.bindLong(1, migration.version.toLong()); it.bindText(2, migration.name)
                    it.bindText(3, "2026-01-01T00:00:00Z"); it.bindText(4, migration.checksum)
                }
            }
            val all = session.schemaScope()
            val oldFingerprint = SchemaFingerprint(session).compute(all.copy(tables = all.tables.filter { it != "refinery_schema_history" })).hash
            assertEquals(upgrade.getValue("fingerprints").jsonObject.getValue("previousVersion").jsonObject.getValue("fingerprint").jsonPrimitive.content, oldFingerprint)
            session.exec("INSERT INTO exam_sheets(id,exam_name,status,temp_id,metadata_json,preview_json,created_at,updated_at) VALUES('exam_fsrs_fixture','fixture','completed','s2','{}','{}','2026-10-20T08:00:00Z','2026-10-20T08:00:00Z')")
            session.exec("INSERT INTO questions(id,exam_id,question_label,content,answer,created_at,updated_at) VALUES('q_due_fixture','exam_fsrs_fixture','1','fixture','A','2026-10-20T08:00:00Z','2026-10-20T08:00:00Z')")
            val columns = before.keys.toList()
            session.execBind("INSERT INTO review_plans(${columns.joinToString(",")}) VALUES(${columns.joinToString(",") { "?" }})") { statement ->
                columns.forEachIndexed { index, key ->
                    val value = before.getValue(key)
                    when {
                        value is JsonNull -> statement.bindNull(index + 1)
                        value.jsonPrimitive.isString -> statement.bindText(index + 1, value.jsonPrimitive.content)
                        '.' in value.jsonPrimitive.content -> statement.bindDouble(index + 1, value.jsonPrimitive.content.toDouble())
                        else -> statement.bindLong(index + 1, value.jsonPrimitive.content.toLong())
                    }
                }
            }
            assertEquals(72, history(session).size)
            session.exec("COMMIT")
        }
        val prefix = SqliteConnectionFactory().open(file).use(::history)
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            assertEquals(20261111, db.migrationReport?.fromVersion)
            assertEquals(20261120, db.migrationReport?.toVersion)
            assertEquals(4, db.migrationReport?.appliedCount)
            assertEquals(CoordinatedSchemaRepair.load(source).after.fingerprint, db.migrationReport?.fingerprint)
            val values = upgrade.getValue("values").jsonObject
            val expected = values.getValue("after").jsonObject + values.getValue("appendedDefaults").jsonObject
            val columns = expected.keys.toList()
            db.query("SELECT ${columns.joinToString(",")} FROM review_plans WHERE id='rp_due_fixture'") { row ->
                columns.forEachIndexed { index, key ->
                    val value = expected.getValue(key)
                    when {
                        value is JsonNull -> assertTrue(key, row.isNull(index))
                        value.jsonPrimitive.isString -> assertEquals(key, value.jsonPrimitive.content, row.text(index))
                        '.' in value.jsonPrimitive.content -> assertEquals(key, value.jsonPrimitive.content.toDouble(), row.double(index), 0.0)
                        else -> assertEquals(key, value.jsonPrimitive.content.toLong(), row.long(index))
                    }
                }
            }.also { assertEquals(1, it.size) }
            assertEquals(listOf("rp_due_fixture"), db.query("SELECT id FROM review_plans WHERE next_review_date <= '2026-11-16' AND status != 'suspended'") { it.text(0) })
            assertTrue(db.query("PRAGMA foreign_key_check") { it.long(0) }.isEmpty())
        }
        SqliteConnectionFactory().open(file).use { session ->
            assertEquals(prefix, history(session).take(prefix.size))
            assertEquals(76, history(session).size)
        }
    }

    fun driftPreservesExistingDatabaseAndLocatesObjects() {
        val base = root.resolve("base.db")
        DatabaseFactory.openShared(base, SharedDatabase.LLM_USAGE, source).close()
        val secret = "sensitive_payload_not_for_diagnostics"
        val cases = listOf(
            Triple("table", "llm_usage_daily", "ALTER TABLE llm_usage_daily ADD COLUMN poisoned TEXT DEFAULT '$secret'"),
            Triple("index", "idx_llm_usage_daily_date", "DROP INDEX idx_llm_usage_daily_date"),
            Triple("trigger", "trg_llm_usage_daily_updated_at", "DROP TRIGGER trg_llm_usage_daily_updated_at"),
        )
        cases.forEachIndexed { index, (type, name, ddl) ->
            val file = root.resolve("drift-$index.db")
            base.copyTo(file)
            SqliteConnectionFactory().open(file).use { session ->
                session.exec(ddl)
                if (type == "trigger") session.exec("CREATE TRIGGER trg_llm_usage_daily_updated_at AFTER UPDATE ON llm_usage_daily BEGIN SELECT '$secret\nidx:idx_llm_usage_daily_date:forged'; END")
            }
            val before = snapshot(file)
            val events = mutableListOf<MigrationLogEvent>()
            val error = assertThrows(MigrationException::class.java) {
                DatabaseFactory.openShared(file, SharedDatabase.LLM_USAGE, source) { events += it }
            }
            assertEquals(MigrationDiagnostic.SCHEMA_DRIFT, error.diagnostic)
            assertEquals(type, error.objectType)
            assertEquals(name, error.objectName)
            assertEquals(name, events.single().objectName)
            assertFalse(error.toString().contains(secret))
            assertFalse(events.toString().contains(secret))
            assertEquals(before, snapshot(file))
        }
        val file = root.resolve("history-drift.db")
        base.copyTo(file)
        SqliteConnectionFactory().open(file).use { it.exec("UPDATE refinery_schema_history SET checksum='$secret' WHERE version=20260130") }
        val prior = snapshot(file)
        val err = assertThrows(MigrationException::class.java) { DatabaseFactory.openShared(file, SharedDatabase.LLM_USAGE, source) }
        assertEquals(MigrationDiagnostic.HISTORY_DRIFT, err.diagnostic)
        assertEquals(20260130, err.version)
        assertEquals(prior, snapshot(file))
        val target = "config/schema/history/llm_usage/V20260130__init.sql"
        val changed = MigrationSource { path -> source.read(path).let { if (path == target) it.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() } else it } }
        val bytesError = assertThrows(MigrationException::class.java) { DatabaseFactory.openShared(base, SharedDatabase.LLM_USAGE, changed) }
        assertEquals(MigrationDiagnostic.SQL_BYTES_DRIFT, bytesError.diagnostic)
        assertEquals(20260130, bytesError.version)
        assertEquals("init", bytesError.objectName)
        val hostile = MigrationSource { throw MigrationException(MigrationDiagnostic.SCHEMA_DRIFT, objectName = secret, causeClass = secret) }
        val hostileEvents = mutableListOf<MigrationLogEvent>()
        val hostileError = assertThrows(MigrationException::class.java) { DatabaseFactory.openShared(base, SharedDatabase.LLM_USAGE, hostile) { hostileEvents += it } }
        assertEquals(MigrationDiagnostic.SOURCE_INVALID, hostileError.diagnostic)
        assertFalse(hostileError.toString().contains(secret))
        assertFalse(hostileEvents.toString().contains(secret))
    }

    fun untrustedHistoryAndOrphanManagedObjectsAreRejected() {
        val base = root.resolve("history-base.db")
        DatabaseFactory.openShared(base, SharedDatabase.LLM_USAGE, source).close()
        for (kind in listOf("orphan", "view", "definition", "type", "trigger", "governance")) {
            val file = root.resolve("untrusted-$kind.db")
            base.copyTo(file)
            SqliteConnectionFactory().open(file).use { session ->
                when (kind) {
                    "orphan" -> session.exec("DROP TABLE refinery_schema_history")
                    "view" -> {
                        session.exec("DROP TABLE refinery_schema_history")
                        session.exec("CREATE VIEW refinery_schema_history AS SELECT 20260130 version,'init' name,'x' applied_on,'x' checksum")
                    }
                    "definition" -> session.exec("ALTER TABLE refinery_schema_history ADD COLUMN injected TEXT")
                    "type" -> session.exec("UPDATE refinery_schema_history SET applied_on=x'ffff' WHERE version=20260130")
                    "trigger" -> session.exec("CREATE TRIGGER hostile_history AFTER INSERT ON refinery_schema_history BEGIN DELETE FROM refinery_schema_history; END")
                    "governance" -> {
                        session.exec("ALTER TABLE __governance_schema_fingerprints RENAME TO old_governance")
                        session.exec("CREATE TABLE __governance_schema_fingerprints(database_id TEXT NOT NULL, schema_version INTEGER NOT NULL, fingerprint TEXT NOT NULL, verified_at TEXT NOT NULL, PRIMARY KEY(database_id,schema_version))")
                        session.exec("INSERT INTO __governance_schema_fingerprints SELECT database_id,schema_version,'bad',verified_at FROM old_governance")
                        session.exec("DROP TABLE old_governance")
                    }
                }
            }
            val before = rawSnapshot(file)
            val error = assertThrows(MigrationException::class.java) { DatabaseFactory.openShared(file, SharedDatabase.LLM_USAGE, source) }
            assertEquals(if (kind == "governance") MigrationDiagnostic.SCHEMA_DRIFT else MigrationDiagnostic.HISTORY_DRIFT, error.diagnostic)
            if (kind == "orphan") assertEquals("table", error.objectType)
            assertEquals(before, rawSnapshot(file))
        }
    }

    private fun rawSnapshot(file: File): List<String> = SqliteConnectionFactory().open(file).use { session ->
        session.queryTexts("SELECT type||':'||name||':'||ifnull(sql,'') FROM sqlite_master ORDER BY type,name") +
            session.queryTexts("SELECT * FROM __governance_schema_fingerprints ORDER BY database_id,schema_version")
    }

    fun migrationAndLedgerRollbackTogether() {
        val file = root.resolve("failure.db")
        val first = migration(1, "first", "CREATE TABLE durable(id INTEGER PRIMARY KEY); INSERT INTO durable VALUES(1);")
        val second = migration(2, "second", "CREATE TABLE transient(id); INSERT INTO missing VALUES(1);")
        SqliteConnectionFactory().open(file).use { session ->
            val error = assertThrows(MigrationException::class.java) {
                MigrationRunner(session, SharedDatabase.LLM_USAGE, listOf(first, second), FrozenSchema(2, "", SchemaScope(emptyList(), emptyList(), emptyList()))).run()
            }
            assertEquals(MigrationDiagnostic.MIGRATION_FAILED, error.diagnostic)
            assertEquals(2, error.version)
            assertEquals(listOf("1:first:checksum-1"), session.queryTexts("SELECT version||':'||name||':'||checksum FROM refinery_schema_history ORDER BY version"))
            assertEquals(1L, session.queryLong("SELECT count(*) FROM durable"))
            assertEquals(0L, session.queryLong("SELECT count(*) FROM sqlite_master WHERE name='transient'"))
        }
    }

    fun foreignKeyRebuildIsAtomic() {
        for (valid in listOf(true, false)) {
            val file = root.resolve("fk-$valid.db")
            val first = migration(1, "setup", "CREATE TABLE parent(id INTEGER PRIMARY KEY); CREATE TABLE child(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES parent(id)); INSERT INTO parent VALUES(1); INSERT INTO child VALUES(1,1);")
            val second = migration(2, "rebuild", "DROP TABLE parent; CREATE TABLE parent(id INTEGER PRIMARY KEY); INSERT INTO parent VALUES(${if (valid) 1 else 2});")
            val schema = if (valid) syntheticSchema(listOf(first, second)) else FrozenSchema(2, "", SchemaScope(emptyList(), emptyList(), emptyList()))
            SqliteConnectionFactory().open(file).use { session ->
                val run = { MigrationRunner(session, SharedDatabase.LLM_USAGE, listOf(first, second), schema).run() }
                if (valid) assertEquals(2, run().appliedCount) else {
                    val error = assertThrows(MigrationException::class.java) { run() }
                    assertEquals(MigrationDiagnostic.MIGRATION_FAILED, error.diagnostic)
                    assertEquals(2, error.version)
                }
                assertEquals(1L, session.queryLong("PRAGMA foreign_keys"))
                assertEquals(1L, session.queryLong("SELECT id FROM parent"))
                assertEquals(1L, session.queryLong("SELECT parent FROM child"))
                assertEquals(if (valid) 2L else 1L, session.queryLong("SELECT count(*) FROM refinery_schema_history"))
                assertTrue(session.queryAll("PRAGMA foreign_key_check") { true }.isEmpty())
            }
        }
    }

    private fun syntheticSchema(migrations: List<FrozenMigration>): FrozenSchema = SqliteConnectionFactory().openMemory().use { session ->
        migrations.forEach { splitStatements(it.sql).forEach(session::exec) }
        val scope = session.schemaScope()
        FrozenSchema(migrations.last().version, SchemaFingerprint(session).compute(scope).hash, scope)
    }

    private fun migration(version: Int, name: String, sql: String) = FrozenMigration("llm_usage", version, name, "test", "", "checksum-$version", sql)

    private fun history(session: SqliteSession): List<String> = session.queryTexts(
        "SELECT version||':'||name||':'||applied_on||':'||checksum FROM refinery_schema_history ORDER BY version",
    )

    private fun governance(session: SqliteSession): List<String> = session.queryTexts(
        "SELECT database_id||':'||schema_version||':'||fingerprint||':'||verified_at||':'||ifnull(canonical_schema,'') FROM __governance_schema_fingerprints ORDER BY database_id,schema_version",
    )

    private fun snapshot(file: File): List<List<String>> = SqliteConnectionFactory().open(file).use { session ->
        listOf(history(session), governance(session), session.queryTexts("SELECT type||':'||name||':'||ifnull(sql,'') FROM sqlite_master ORDER BY type,name"))
    }
}
