package com.deepstudent.platform.database

import com.deepstudent.platform.database.internal.DatabaseEngine
import com.deepstudent.platform.database.internal.SqliteSession
import com.deepstudent.platform.database.internal.toDatabaseError
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseRegistryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var file: File
    private val pool = Executors.newCachedThreadPool()
    private val profile = DatabaseProfile(lockWaitMillis = 350, maxReaders = 2)

    @Before fun setUp() { file = temporary.root.resolve("private.db") }
    @After fun tearDown() { pool.shutdownNow(); pool.awaitTermination(3, TimeUnit.SECONDS) }

    @Test fun busyAndLockedExtendedCodesRemainBusyEvenDuringOpen() {
        for (code in listOf(5, 6, 261, 262, 517, 518)) {
            val error = RuntimeException("Error code: $code").toDatabaseError("open")
            assertEquals(DatabaseErrorKind.BUSY, error.kind)
            assertEquals(DatabaseDiagnostic.BUSY_LOCK, error.diagnostic)
            assertEquals(code, error.sqliteCode)
        }
        val nested = RuntimeException("outer", RuntimeException("Error code: 517"))
            .toDatabaseError("open")
        assertEquals(DatabaseErrorKind.BUSY, nested.kind)
    }

    @Test fun pinnedWalSnapshotPrecedesCallbackAndConcurrentCommit() {
        DatabaseRegistry.openLocal(file, profile).use { db ->
            db.execute("CREATE TABLE t(n INTEGER)")
            db.execute("INSERT INTO t VALUES (1)")
            val entered = CountDownLatch(1)
            val committed = CountDownLatch(1)
            val reader = pool.submit<List<Long>> {
                db.readSnapshot {
                    entered.countDown()
                    check(committed.await(3, TimeUnit.SECONDS))
                    val first = query("SELECT n FROM t ORDER BY n") { it.long(0) }
                    val second = query("SELECT n FROM t ORDER BY n") { it.long(0) }
                    assertEquals(first, second)
                    first
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            try { db.execute("INSERT INTO t VALUES (2)") } finally { committed.countDown() }
            assertEquals(listOf(1L), reader.get(3, TimeUnit.SECONDS))
            assertEquals(listOf(1L, 2L), db.query("SELECT n FROM t ORDER BY n") { it.long(0) })
        }
    }

    @Test fun profilesAndPragmasAreSetOnReadersWithoutChangingDurability() {
        val normal = profile.copy(synchronous = DatabaseSynchronous.NORMAL)
        DatabaseRegistry.openLocal(file, normal).use { db ->
            assertEquals(listOf("wal"), db.query("PRAGMA journal_mode") { it.text(0).lowercase() })
            assertEquals(listOf(1L), db.query("PRAGMA foreign_keys") { it.long(0) })
            assertEquals(listOf(1L), db.query("PRAGMA synchronous") { it.long(0) })
            val timeout = db.query("PRAGMA busy_timeout") { it.long(0) }.single()
            assertTrue(timeout in 0..profile.lockWaitMillis)
            assertThrows(MigrationException::class.java) { db.execute("PRAGMA foreign_keys=OFF") }
        }
    }

    @Test fun snapshotIsReadOnlyAndScopesCannotEscapeOrChangeThreads() {
        DatabaseRegistry.openLocal(file, profile).use { db ->
            db.execute("CREATE TABLE t(n INTEGER)")
            db.execute("INSERT INTO t VALUES (1)")
            lateinit var escaped: SqlReadOperations
            lateinit var row: SqlRow
            db.readSnapshot {
                escaped = this
                row = query("SELECT n FROM t") { it }.single()
                assertThrows(MigrationException::class.java) { db.execute("INSERT INTO t VALUES (2)") }
                assertThrows(Exception::class.java) { query("INSERT INTO t VALUES (3) RETURNING n") { it.long(0) } }
                val failure = pool.submit<Boolean> {
                    try { escaped.query("SELECT 1") { it.long(0) }; false } catch (_: MigrationException) { true }
                }
                assertTrue(failure.get(3, TimeUnit.SECONDS))
            }
            assertThrows(MigrationException::class.java) { escaped.query("SELECT 1") { it.long(0) } }
            assertThrows(MigrationException::class.java) { row.long(0) }
            assertEquals(listOf(1L), db.query("SELECT n FROM t") { it.long(0) })
        }
    }

    @Test fun sameHandleReentrancyUsesTransactionAndRollbackDoesNotEscape() {
        DatabaseRegistry.openLocal(file, profile).use { db ->
            db.execute("CREATE TABLE t(n INTEGER)")
            assertThrows(IllegalArgumentException::class.java) {
                db.transaction {
                    execute("INSERT INTO t VALUES (1)")
                    db.execute("INSERT INTO t VALUES (2)")
                    assertEquals(listOf(2L), db.query("SELECT count(*) FROM t") { it.long(0) })
                    throw IllegalArgumentException("test failure")
                }
            }
            assertEquals(listOf(0L), db.query("SELECT count(*) FROM t") { it.long(0) })
            db.transaction {
                assertThrows(MigrationException::class.java) { db.transaction { } }
                assertThrows(MigrationException::class.java) { db.close() }
            }
        }
    }

    @Test fun autoRollbackDoesNotProduceFakeSuppressedFailureOrAllowAutocommitEscape() {
        DatabaseRegistry.openLocal(file, profile).use { db ->
            db.execute("CREATE TABLE t(n INTEGER UNIQUE)")
            db.execute("INSERT INTO t VALUES(1)")
            val error = assertThrows(Exception::class.java) {
                db.transaction { execute("INSERT OR ROLLBACK INTO t VALUES(1)") }
            }
            assertTrue(error.suppressed.isEmpty())
            assertThrows(MigrationException::class.java) {
                db.transaction {
                    try { execute("INSERT OR ROLLBACK INTO t VALUES(1)") } catch (_: Exception) { }
                    execute("INSERT INTO t VALUES(2)")
                }
            }
            db.execute("INSERT INTO t VALUES(3)")
            assertEquals(listOf(1L, 3L), db.query("SELECT n FROM t ORDER BY n") { it.long(0) })
        }
    }


    @Test fun bomCannotHideTransactionControlOrConnectionPragmas() {
        DatabaseRegistry.openLocal(file, profile).use { db ->
            db.execute("CREATE TABLE t(n INTEGER)")
            db.transaction {
                for (sql in listOf("\uFEFFBEGIN", "\uFEFF -- comment\n COMMIT", "\uFEFFROLLBACK",
                    "\uFEFFSAVEPOINT p", "\uFEFFPRAGMA foreign_keys=OFF", "\uFEFFATTACH DATABASE ':memory:' AS other")) {
                    val error = assertThrows(MigrationException::class.java) { execute(sql) }
                    assertEquals(MigrationDiagnostic.STATEMENT_INVALID, error.diagnostic)
                }
                execute("\uFEFFINSERT INTO t VALUES (1)")
            }
            assertEquals(listOf(1L), db.query("\uFEFFSELECT n FROM t") { it.long(0) })
        }
    }

    @Test fun explainCannotHidePrepareTimePragmaOrTransactionControl() {
        DatabaseRegistry.openLocal(file, profile).use { db ->
            db.transaction {
                for (sql in listOf("EXPLAIN PRAGMA foreign_keys=OFF", "EXPLAIN QUERY PLAN PRAGMA busy_timeout=999999",
                    "\uFEFFEXPLAIN /* gap */ QUERY -- gap\n PLAN \uFEFFPRAGMA query_only=OFF",
                    "EXPLAIN PRAGMA wal_autocheckpoint=0", "EXPLAIN /* gap */ BEGIN")) {
                    assertEquals(MigrationDiagnostic.STATEMENT_INVALID,
                        assertThrows(MigrationException::class.java) { execute(sql) }.diagnostic)
                }
                assertTrue(query("EXPLAIN SELECT 1") { it.long(0) }.isNotEmpty())
                assertEquals(listOf(1L), query("PRAGMA foreign_keys") { it.long(0) })
                assertTrue(query("PRAGMA busy_timeout") { it.long(0) }.single() <= profile.lockWaitMillis)
            }
        }
    }

    @Test fun triggerRollbackCaughtByCallbackStillInvalidatesTheEntireTransaction() {
        DatabaseRegistry.openLocal(file, profile).use { db ->
            db.execute("CREATE TABLE t(n INTEGER)")
            db.execute("CREATE TRIGGER stop_insert BEFORE INSERT ON t BEGIN SELECT RAISE(ROLLBACK, 'stop'); END")
            assertThrows(MigrationException::class.java) {
                db.transaction {
                    try { execute("INSERT INTO t VALUES(1)") } catch (_: Exception) { }
                    // Returning normally after SQLite auto-rollback must not report success.
                }
            }
            assertEquals(listOf(0L), db.query("SELECT count(*) FROM t") { it.long(0) })
        }
    }

    @Test fun failedRollbackPoisonsTheConnectionInsteadOfReusingAnOpenTransaction() {
        val driver = RefusesRollback()
        val engine = DatabaseEngine(file, profile, SqliteSession(driver), null)
        val error = assertThrows(IllegalStateException::class.java) {
            engine.write(this) { throw IllegalStateException("caller") }
        }
        assertEquals(1, error.suppressed.size)
        assertThrows(MigrationException::class.java) { engine.write(this) { } }
        assertThrows(MigrationException::class.java) { engine.read(this) { } }
        engine.close()
        assertTrue(driver.closed)
    }

    @Test fun leasesAreCanonicalAndCannotOverrideAProfileOrCloseEachOther() {
        DatabaseRegistry.openLocal(file, profile).use { first ->
            val alias = file.parentFile.resolve("alias.db")
            Files.createSymbolicLink(alias.toPath(), file.toPath())
            val second = DatabaseRegistry.openLocal(alias, profile)
            first.execute("CREATE TABLE t(n INTEGER)")
            assertThrows(DatabaseConfigurationException::class.java) {
                DatabaseRegistry.openLocal(file, profile.copy(lockWaitMillis = 500))
            }
            first.transaction {
                assertThrows(MigrationException::class.java) { second.query("SELECT 1") { it.long(0) } }
                execute("INSERT INTO t VALUES(1)")
            }
            second.close()
            assertThrows(MigrationException::class.java) { second.query("SELECT 1") { it.long(0) } }
            assertEquals(listOf(1L), first.query("SELECT n FROM t") { it.long(0) })
        }
    }

    @Test fun hardLinkAliasIsRejectedBecauseWalSidecarsWouldNotBeShared() {
        DatabaseRegistry.openLocal(file, profile).use {
            val alias = file.parentFile.resolve("hard.db")
            Files.createLink(alias.toPath(), file.toPath())
            assertThrows(DatabaseConfigurationException::class.java) { DatabaseRegistry.openLocal(alias, profile) }
        }
    }

    @Test fun movingAnActiveFileCannotCreateAnotherWriterOrSwapTheReaders() {
        DatabaseRegistry.openLocal(file, profile).use { db ->
            db.execute("CREATE TABLE t(n INTEGER)")
            val moved = file.parentFile.resolve("moved.db")
            Files.move(file.toPath(), moved.toPath())
            try {
                assertThrows(DatabaseConfigurationException::class.java) { DatabaseRegistry.openLocal(moved, profile) }
                assertThrows(DatabaseConfigurationException::class.java) { DatabaseRegistry.openLocal(file, profile) }
                assertThrows(DatabaseConfigurationException::class.java) { db.execute("INSERT INTO t VALUES(1)") }
                assertThrows(DatabaseConfigurationException::class.java) { db.query("SELECT * FROM t") { it.long(0) } }
            } finally { Files.move(moved.toPath(), file.toPath()) }
            db.execute("INSERT INTO t VALUES(2)")
        }
    }

    @Test fun exhaustedQueueCannotCommitLater() {
        DatabaseRegistry.openLocal(file, profile).use { db ->
            db.execute("CREATE TABLE t(n INTEGER)")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val first = pool.submit {
                db.transaction { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            try {
                val error = assertThrows(DatabaseBusyException::class.java) { db.execute("INSERT INTO t VALUES (9)") }
                assertEquals(DatabaseWait.WRITER, error.stage)
            } finally { release.countDown() }
            first.get(3, TimeUnit.SECONDS)
            assertEquals(listOf(0L), db.query("SELECT count(*) FROM t") { it.long(0) })
        }
    }

    @Test fun interruptingAQueuedWriterCancelsWithoutALateCommit() {
        DatabaseRegistry.openLocal(file, profile).use { db ->
            db.execute("CREATE TABLE t(n INTEGER)")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val running = pool.submit { db.transaction { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val attempting = CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val wasInterrupted = java.util.concurrent.atomic.AtomicBoolean(false)
            val thread = Thread {
                attempting.countDown()
                try { db.execute("INSERT INTO t VALUES(9)") }
                catch (error: Throwable) { failure.set(error); wasInterrupted.set(Thread.currentThread().isInterrupted) }
            }
            thread.start()
            try {
                assertTrue(attempting.await(2, TimeUnit.SECONDS))
                thread.interrupt()
                thread.join(2000)
                assertFalse(thread.isAlive)
                assertTrue(failure.get() is java.util.concurrent.CancellationException)
                assertTrue(wasInterrupted.get())
            } finally { release.countDown() }
            running.get(3, TimeUnit.SECONDS)
            assertEquals(listOf(0L), db.query("SELECT count(*) FROM t") { it.long(0) })
        }
    }

    @Test fun readerPoolAndCloseHaveFiniteWaitAndDeferredRelease() {
        val small = profile.copy(lockWaitMillis = 180, maxReaders = 1)
        val db = DatabaseRegistry.openLocal(file, small)
        db.execute("CREATE TABLE t(n INTEGER)")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = pool.submit { db.readSnapshot { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        try {
            val read = assertThrows(DatabaseBusyException::class.java) { db.query("SELECT 1") { it.long(0) } }
            assertEquals(DatabaseWait.READER, read.stage)
            val close = assertThrows(DatabaseBusyException::class.java) { db.close() }
            assertEquals(DatabaseWait.CLOSE, close.stage)
            assertThrows(MigrationException::class.java) { db.execute("INSERT INTO t VALUES(9)") }
        } finally { release.countDown() }
        first.get(3, TimeUnit.SECONDS)
        db.close()
        DatabaseRegistry.openLocal(file, small).use { assertEquals(listOf(0L), it.query("SELECT count(*) FROM t") { row -> row.long(0) }) }
    }

    @Test fun externalProcessLockAndWriterQueueConsumeOneCombinedBudget() {
        DatabaseRegistry.openLocal(file, profile).use { db ->
            db.execute("CREATE TABLE t(n INTEGER)")
            val locker = externalLocker(file)
            try {
                val started = CountDownLatch(1)
                val first = pool.submit<Throwable?> {
                    started.countDown()
                    try { db.execute("INSERT INTO t VALUES(1)"); null } catch (error: Throwable) { error }
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                Thread.sleep(120)
                val start = System.nanoTime()
                val second = assertThrows(DatabaseBusyException::class.java) { db.execute("INSERT INTO t VALUES(2)") }
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                assertEquals(DatabaseWait.SQLITE, second.stage)
                assertTrue("elapsed=$elapsed", elapsed in 250..650)
                assertEquals(DatabaseWait.SQLITE, (first.get(2, TimeUnit.SECONDS) as DatabaseBusyException).stage)
            } finally { stopLocker(locker) }
            assertEquals(listOf(0L), db.query("SELECT count(*) FROM t") { it.long(0) })
            db.execute("INSERT INTO t VALUES(3)")
        }
    }

    @Test fun openingAnExternallyLockedNonWalFileReportsBusyWithoutLeakingARegistrySlot() {
        val locker = externalLocker(file)
        try {
            val start = System.nanoTime()
            val error = assertThrows(DatabaseBusyException::class.java) { DatabaseRegistry.openLocal(file, profile) }
            assertEquals(DatabaseWait.SQLITE, error.stage)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("elapsed=$elapsed", elapsed < 1500)
        } finally { stopLocker(locker) }
        DatabaseRegistry.openLocal(file, profile).use { it.execute("CREATE TABLE t(n INTEGER)") }
    }

    @Test fun closedLeasesReturnDatabaseDescriptorsToBaseline() {
        DatabaseRegistry.openLocal(file, profile).use { it.execute("CREATE TABLE t(n INTEGER)") }
        val baseline = descriptorsFor(file)
        repeat(25) {
            DatabaseRegistry.openLocal(file, profile).use { db ->
                db.transaction { execute("INSERT INTO t VALUES(1)") }
                db.readSnapshot { query("SELECT count(*) FROM t") { it.long(0) } }
                assertTrue(descriptorsFor(file) > baseline)
            }
            assertEquals(baseline, descriptorsFor(file))
        }
    }

    @Test fun logsAndBusyExceptionsDoNotExposePathsOrCallerValues() {
        val secret = "private-secret-9328"
        file = temporary.root.resolve(secret)
        val events = mutableListOf<DatabaseLogEvent>()
        DatabaseRegistry.openLocal(file, profile, DatabaseLog { events += it }).use { db ->
            assertThrows(Exception::class.java) { db.execute("INSERT INTO $secret VALUES(1)") }
        }
        assertTrue(events.any { it.phase == DatabasePhase.WRITE && it.status == DatabaseStatus.FAILED })
        assertFalse(events.toString().contains(secret))
    }

    private fun externalLocker(file: File): Process {
        val script = "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute('BEGIN IMMEDIATE'); print('ready',flush=True); sys.stdin.readline(); c.rollback(); c.close()"
        val process = ProcessBuilder("python3", "-u", "-c", script, file.absolutePath).redirectErrorStream(true).start()
        assertEquals("ready", process.inputStream.bufferedReader().readLine())
        return process
    }

    private fun stopLocker(process: Process) {
        process.outputStream.write('\n'.code)
        process.outputStream.flush()
        assertTrue(process.waitFor(3, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }

    private fun descriptorsFor(file: File): Int = File("/proc/self/fd").listFiles().orEmpty().count {
        val target = runCatching { Files.readSymbolicLink(it.toPath()).toString() }.getOrDefault("")
        target == file.absolutePath || target == file.absolutePath + "-wal" || target == file.absolutePath + "-shm"
    }
}

private class RefusesRollback : SQLiteConnection {
    var transaction = false
    var closed = false
    override fun prepare(sql: String): SQLiteStatement {
        if (sql == "ROLLBACK") throw IllegalStateException("fake rollback failure")
        return object : SQLiteStatement {
            override fun step(): Boolean { if (sql == "BEGIN IMMEDIATE") transaction = true; return false }
            override fun reset() { }
            override fun close() { }
            override fun clearBindings() { }
            override fun bindBlob(index: Int, value: ByteArray) { }
            override fun bindDouble(index: Int, value: Double) { }
            override fun bindLong(index: Int, value: Long) { }
            override fun bindNull(index: Int) { }
            override fun bindText(index: Int, value: String) { }
            override fun getBlob(index: Int): ByteArray = error("unused")
            override fun getDouble(index: Int): Double = error("unused")
            override fun getLong(index: Int): Long = error("unused")
            override fun getText(index: Int): String = error("unused")
            override fun isNull(index: Int): Boolean = error("unused")
            override fun getColumnCount(): Int = 0
            override fun getColumnName(index: Int): String = error("unused")
            override fun getColumnType(index: Int): Int = error("unused")
        }
    }
    override fun inTransaction(): Boolean = transaction
    override fun close() { closed = true }
}
