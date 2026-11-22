package com.deepstudent.platform.database

import com.deepstudent.platform.database.internal.FrozenCatalogLoader
import com.deepstudent.platform.database.internal.FrozenMigration
import com.deepstudent.platform.database.internal.FrozenSchema
import com.deepstudent.platform.database.internal.MigrationRunner
import com.deepstudent.platform.database.internal.SchemaScope
import com.deepstudent.platform.database.internal.sha256
import com.deepstudent.platform.database.internal.SqliteConnectionFactory
import com.deepstudent.platform.database.internal.refineryChecksum
import com.deepstudent.platform.database.internal.splitStatements
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MigrationRunnerTest {
    private val root = Files.createTempDirectory("s1-05-jvm").toFile()
    private val repository = File(System.getProperty("user.dir")).let {
        if (File(it, "config/schema/final-lock.json").isFile) it else it.resolve("../..")
    }
    private val source = MigrationSource { repository.resolve(it).readBytes() }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun allFrozenFilesAndChecksumsAreRead() {
        val catalog = FrozenCatalogLoader.load(source)
        assertEquals(139, catalog.migrations.size)
        assertEquals(1, catalog.migrations.count { it.databaseId == "browser" })
        assertEquals(76, catalog.forDatabase(SharedDatabase.VFS).size)
        assertEquals(30, catalog.forDatabase(SharedDatabase.CHAT_V2).size)
        assertEquals(24, catalog.forDatabase(SharedDatabase.MISTAKES).size)
        assertEquals(8, catalog.forDatabase(SharedDatabase.LLM_USAGE).size)
        assertEquals("744929442360551984", refineryChecksum(
            "init", 20260130, source.read("config/schema/history/chat_v2/V20260130__init.sql"),
        ))
    }

    @Test fun fourDatabasesMatchDesktopGoldenAndRestartWithZeroMigrations() {
        val expected = mapOf(
            SharedDatabase.VFS to (76 to "7dec0d2ff3cf1c40cb15e73396c05cbcab925487661b3e09a69a6b1f30f8345b"),
            SharedDatabase.CHAT_V2 to (30 to "2a29bf29771c7381db4cac8cdbad325cddad8d86bc06795a369176e4654e8b7a"),
            SharedDatabase.MISTAKES to (24 to "fdd31bdf68ccfa505b88e946a24eb4ae66dc43e13ccf3102f045a67af36b4e31"),
            SharedDatabase.LLM_USAGE to (8 to "ed69ae106103c3b48460d898be47fdfe12124e26f392e4636d1fe7816beb6245"),
        )
        for ((database, result) in expected) {
            val file = root.resolve("${database.id}.db")
            DatabaseFactory.openShared(file, database, source).use { db ->
                assertEquals(result.first, db.migrationReport?.appliedCount)
                assertEquals(result.second, db.migrationReport?.fingerprint)
                assertEquals(listOf("ok"), db.query("PRAGMA integrity_check") { it.text(0) })
                assertTrue(db.query("PRAGMA foreign_key_check") { it.long(0) }.isEmpty())
                val governance = db.query("SELECT fingerprint,canonical_schema FROM __governance_schema_fingerprints") {
                    it.text(0) to it.text(1)
                }.single()
                assertEquals(result.second, governance.first)
                assertEquals(result.second, sha256(governance.second.toByteArray()))
            }
            DatabaseFactory.openShared(file, database, source).use { db ->
                assertEquals(0, db.migrationReport?.appliedCount)
                assertEquals(result.second, db.migrationReport?.fingerprint)
            }
        }
    }

    @Test fun activeSharedLeasesRevalidateThroughTheSameWriter() {
        val file = root.resolve("shared-active.db")
        DatabaseFactory.openShared(file, SharedDatabase.LLM_USAGE, source).use { first ->
            DatabaseFactory.openShared(file, SharedDatabase.LLM_USAGE, source).use { second ->
                assertEquals(0, second.migrationReport?.appliedCount)
            }
            first.execute("DROP TRIGGER trg_llm_usage_daily_updated_at")
            val error = assertThrows(MigrationException::class.java) {
                DatabaseFactory.openShared(file, SharedDatabase.LLM_USAGE, source)
            }
            assertEquals(MigrationDiagnostic.SCHEMA_DRIFT, error.diagnostic)
            assertEquals("trg_llm_usage_daily_updated_at", error.objectName)
        }
    }

    @Test fun oneByteDriftIdentifiesTheFrozenMigrationAndDoesNotOpenDatabase() {
        val target = "config/schema/history/vfs/V20260130__init.sql"
        val tampered = MigrationSource { path -> source.read(path).let { if (path == target) it + 10 else it } }
        val file = root.resolve("not-opened.db")
        val error = assertThrows(MigrationException::class.java) {
            DatabaseFactory.openShared(file, SharedDatabase.VFS, tampered)
        }
        assertEquals(MigrationDiagnostic.SQL_BYTES_DRIFT, error.diagnostic)
        assertEquals(20260130, error.version)
        assertEquals("init", error.objectName)
        assertFalse(file.exists())
    }

    @Test fun historyAndManagedDdlDriftAreRejectedAndLocated() {
        val file = root.resolve("drift.db")
        DatabaseFactory.openShared(file, SharedDatabase.LLM_USAGE, source).close()
        SqliteConnectionFactory().open(file).use { it.exec("DROP TRIGGER trg_llm_usage_daily_updated_at") }
        val error = assertThrows(MigrationException::class.java) {
            DatabaseFactory.openShared(file, SharedDatabase.LLM_USAGE, source)
        }
        assertEquals(MigrationDiagnostic.SCHEMA_DRIFT, error.diagnostic)
        assertEquals("trigger", error.objectType)
        assertEquals("trg_llm_usage_daily_updated_at", error.objectName)
        SqliteConnectionFactory().open(file).use { it.exec("UPDATE refinery_schema_history SET checksum='0' WHERE version=20260130") }
        val history = assertThrows(MigrationException::class.java) {
            DatabaseFactory.openShared(file, SharedDatabase.LLM_USAGE, source)
        }
        assertEquals(MigrationDiagnostic.HISTORY_DRIFT, history.diagnostic)
        assertEquals(20260130, history.version)
    }

    @Test fun tamperedGovernanceRecordIsRejected() {
        val file = root.resolve("governance.db")
        DatabaseFactory.openShared(file, SharedDatabase.LLM_USAGE, source).close()
        SqliteConnectionFactory().open(file).use {
            it.exec("UPDATE __governance_schema_fingerprints SET fingerprint='invalid'")
        }
        val error = assertThrows(MigrationException::class.java) {
            DatabaseFactory.openShared(file, SharedDatabase.LLM_USAGE, source)
        }
        assertEquals(MigrationDiagnostic.SCHEMA_DRIFT, error.diagnostic)
        assertEquals("governance", error.objectType)
        assertEquals(20260826, error.version)
    }

    @Test fun historyVersionCannotWrapIntoTheExpected32BitValue() {
        val file = root.resolve("history-overflow.db")
        DatabaseFactory.openShared(file, SharedDatabase.LLM_USAGE, source).close()
        SqliteConnectionFactory().open(file).use {
            it.exec("UPDATE refinery_schema_history SET version=version+4294967296 WHERE version=20260826")
        }
        val error = assertThrows(MigrationException::class.java) {
            DatabaseFactory.openShared(file, SharedDatabase.LLM_USAGE, source)
        }
        assertEquals(MigrationDiagnostic.HISTORY_DRIFT, error.diagnostic)
        assertEquals(20260826, error.version)
        assertEquals("add_stream_identity", error.objectName)
    }

    @Test fun localPortBindsMapsAndRollsBackWithoutSharedHistory() {
        val file = root.resolve("runtime.db")
        DatabaseFactory.openLocal(file).use { db ->
            assertEquals(null, db.migrationReport)
            db.execute("CREATE TABLE event(id INTEGER, label TEXT, payload BLOB, optional TEXT)")
            val blob = byteArrayOf(0, 1, 0, -1)
            db.transaction {
                execute("INSERT INTO event VALUES(?,?,?,?)", listOf(
                    SqlValue.Integer(Long.MAX_VALUE), SqlValue.Text("中文"), SqlValue.Blob(blob), SqlValue.Null,
                ))
            }
            assertThrows(IllegalStateException::class.java) {
                db.transaction {
                    execute("INSERT INTO event VALUES(2,'rollback',NULL,NULL)")
                    throw IllegalStateException("fixture")
                }
            }
            val rows = db.query("SELECT id,label,hex(payload),optional FROM event") {
                listOf(it.long(0).toString(), it.text(1), it.text(2), it.isNull(3).toString())
            }
            assertEquals(listOf(listOf(Long.MAX_VALUE.toString(), "中文", "000100FF", "true")), rows)
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE name='refinery_schema_history'") { it.text(0) }.isEmpty())
        }
        DatabaseFactory.openLocal(file).use { db -> assertEquals(listOf(1L), db.query("SELECT count(*) FROM event") { it.long(0) }) }
    }

    @Test fun transactionAndRowCannotEscapeTheirOwnerOrCallback() {
        DatabaseFactory.openLocal(root.resolve("scope.db")).use { db ->
            db.execute("CREATE TABLE t(n INTEGER)")
            lateinit var escaped: SqlOperations
            db.transaction { escaped = this; execute("INSERT INTO t VALUES (1)") }
            assertThrows(MigrationException::class.java) { escaped.execute("INSERT INTO t VALUES (2)") }
            val row = db.query("SELECT n FROM t") { it }.single()
            assertThrows(MigrationException::class.java) { row.long(0) }
        }
    }

    @Test fun transactionsCannotBeControlledBySqlOrEscapedThreads() {
        DatabaseFactory.openLocal(root.resolve("owner.db")).use { db ->
            db.execute("CREATE TABLE t(n INTEGER)")
            db.transaction {
                for (sql in listOf("BEGIN", "-- comment\nCOMMIT", "ROLLBACK", "SAVEPOINT p", "RELEASE p", "PRAGMA foreign_keys=OFF")) {
                    val error = assertThrows(MigrationException::class.java) { execute(sql) }
                    assertEquals(MigrationDiagnostic.STATEMENT_INVALID, error.diagnostic)
                }
                var failed = false
                val worker = Thread {
                    failed = try { execute("INSERT INTO t VALUES(1)"); false } catch (_: MigrationException) { true }
                }
                worker.start()
                worker.join(2000)
                assertFalse(worker.isAlive)
                assertTrue(failed)
                execute("INSERT INTO t VALUES(2)")
            }
            assertEquals(listOf(2L), db.query("SELECT n FROM t") { it.long(0) })
        }
    }

    @Test fun localConnectionsUseWalAndWaitForTheWriter() {
        val file = root.resolve("wal.db")
        DatabaseFactory.openLocal(file).use { first ->
            DatabaseFactory.openLocal(file).use { second ->
                assertEquals(listOf("wal"), first.query("PRAGMA journal_mode") { it.text(0).lowercase() })
                // The read connection exposes the remaining per-operation budget, not a fixed timeout.
                assertTrue(second.query("PRAGMA busy_timeout") { it.long(0) }.single() in 0L..5000L)
                assertEquals(listOf(1L), first.query("PRAGMA foreign_keys") { it.long(0) })
                first.execute("CREATE TABLE t(n INTEGER)")
                val started = CountDownLatch(1)
                lateinit var worker: Thread
                first.transaction {
                    execute("INSERT INTO t VALUES(1)")
                    worker = Thread { started.countDown(); second.execute("INSERT INTO t VALUES(2)") }
                    worker.start()
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    Thread.sleep(150)
                    assertTrue(worker.isAlive)
                }
                worker.join(3000)
                assertFalse(worker.isAlive)
                assertEquals(listOf(2L), first.query("SELECT count(*) FROM t") { it.long(0) })
            }
        }
    }

    @Test fun failedMigrationRollsBackSqlAndReportsItsVersion() {
        SqliteConnectionFactory().open(root.resolve("failure.db")).use { session ->
            val migration = FrozenMigration("llm_usage", 123, "failing", "fixture", "", "0", "CREATE TABLE transient(id); INSERT INTO missing VALUES(1);")
            val runner = MigrationRunner(session, SharedDatabase.LLM_USAGE, listOf(migration), FrozenSchema(123, "", SchemaScope(emptyList(), emptyList(), emptyList())))
            val error = assertThrows(MigrationException::class.java) { runner.run() }
            assertEquals(MigrationDiagnostic.MIGRATION_FAILED, error.diagnostic)
            assertEquals(123, error.version)
            assertEquals("failing", error.objectName)
            assertTrue(session.queryTexts("SELECT name FROM sqlite_master WHERE name='transient'").isEmpty())
            assertEquals(0L, session.queryLong("SELECT count(*) FROM sqlite_master WHERE name='refinery_schema_history'"))
        }
    }

    @Test fun frozenPreUpgradeDatabasePreservesRowsAndAppliesOnlyNewMigrations() {
        val catalog = FrozenCatalogLoader.load(source)
        for ((database, previous, expected) in listOf(
            Triple(SharedDatabase.VFS, 20261115, 3), Triple(SharedDatabase.MISTAKES, 20261005, 1),
        )) {
            val file = root.resolve("upgrade-${database.id}.db")
            SqliteConnectionFactory().open(file).use { session ->
                session.exec("CREATE TABLE refinery_schema_history(version int4 PRIMARY KEY, name VARCHAR(255), applied_on VARCHAR(255), checksum VARCHAR(255))")
                for (migration in catalog.forDatabase(database).filter { it.version <= previous }) {
                    splitStatements(migration.sql).forEach(session::exec)
                    session.exec(
                        "INSERT INTO refinery_schema_history VALUES(" +
                            "${migration.version},'${migration.name}','2026-01-01T00:00:00Z','${migration.checksum}')",
                    )
                }
                session.exec("CREATE TABLE local_upgrade_fixture(value TEXT)")
                session.exec("INSERT INTO local_upgrade_fixture VALUES('preserved')")
            }
            DatabaseFactory.openShared(file, database, source).use { db ->
                assertEquals(previous, db.migrationReport?.fromVersion)
                assertEquals(expected, db.migrationReport?.appliedCount)
                assertEquals(listOf("preserved"), db.query("SELECT value FROM local_upgrade_fixture") { it.text(0) })
            }
        }
    }

    @Test fun preUpgradeDdlDriftIsNotMaskedByLaterMigrations() {
        val catalog = FrozenCatalogLoader.load(source)
        val file = root.resolve("old-drift.db")
        val old = catalog.forDatabase(SharedDatabase.MISTAKES).filter { it.version <= 20261005 }
        SqliteConnectionFactory().open(file).use { session ->
            session.exec("CREATE TABLE refinery_schema_history(version int4 PRIMARY KEY, name VARCHAR(255), applied_on VARCHAR(255), checksum VARCHAR(255))")
            for (migration in old) {
                splitStatements(migration.sql).forEach(session::exec)
                session.exec("INSERT INTO refinery_schema_history VALUES(${migration.version},'${migration.name}','2026-01-01T00:00:00Z','${migration.checksum}')")
            }
            session.exec("DROP INDEX idx_chat_turn_id")
        }
        val error = assertThrows(MigrationException::class.java) {
            DatabaseFactory.openShared(file, SharedDatabase.MISTAKES, source)
        }
        assertEquals(MigrationDiagnostic.SCHEMA_DRIFT, error.diagnostic)
        assertEquals(20261005, error.version)
        assertEquals("idx_chat_turn_id", error.objectName)
        SqliteConnectionFactory().open(file).use { session ->
            assertEquals(old.size.toLong(), session.queryLong("SELECT count(*) FROM refinery_schema_history"))
        }
    }

    @Test fun triggerAndQuotedSemicolonsRemainSingleStatements() {
        val sql = """
            CREATE TABLE t(x TEXT); -- comment ;
            CREATE TABLE log(x TEXT);
            CREATE TRIGGER t_ai AFTER INSERT ON t BEGIN
              INSERT INTO log VALUES('first; ''quoted''');
              INSERT INTO log VALUES(CASE WHEN NEW.x=';' THEN 'yes' ELSE 'no' END);
            END;
            INSERT INTO t VALUES(';');
        """.trimIndent()
        assertEquals(4, splitStatements(sql).size)
        DatabaseFactory.openLocal(root.resolve("trigger.db")).use { db ->
            splitStatements(sql).forEach { db.execute(it) }
            assertEquals(listOf("first; 'quoted'", "yes"), db.query("SELECT x FROM log ORDER BY rowid") { it.text(0) })
            assertThrows(MigrationException::class.java) { db.execute("SELECT 1; SELECT 2") }
        }
    }

    @Test fun sanitizedFailuresAndEventsDoNotContainSecrets() {
        val secret = "sentinel-sensitive-path-and-sql"
        val events = mutableListOf<MigrationLogEvent>()
        val bad = MigrationSource { throw IllegalStateException(secret) }
        val error = assertThrows(MigrationException::class.java) {
            DatabaseFactory.openShared(root.resolve(secret), SharedDatabase.VFS, bad) { events += it }
        }
        assertNotNull(error.causeClass)
        assertFalse(error.toString().contains(secret))
        assertFalse(events.toString().contains(secret))
        assertEquals("platform/database", events.single().owner)
        assertEquals("FAILED", events.single().status)
    }
}
