package com.deepstudent.platform.database

import android.system.Os
import android.util.Log
import com.deepstudent.platform.database.internal.SqliteConnectionFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseRegistryAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val profile = DatabaseProfile(lockWaitMillis = 350, maxReaders = 2)

    @Test fun walSnapshotRemainsPinnedWhileAWriterCommits() = withFile { file ->
        val pool = Executors.newSingleThreadExecutor()
        try {
            DatabaseRegistry.openLocal(file, profile).use { db ->
                db.execute("CREATE TABLE t(n INTEGER)")
                db.execute("INSERT INTO t VALUES(1)")
                val started = CountDownLatch(1)
                val committed = CountDownLatch(1)
                val snapshot = pool.submit<List<Long>> {
                    db.readSnapshot {
                        started.countDown()
                        check(committed.await(5, TimeUnit.SECONDS))
                        val first = query("SELECT n FROM t") { it.long(0) }
                        assertEquals(first, query("SELECT n FROM t") { it.long(0) })
                        first
                    }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                try { db.execute("INSERT INTO t VALUES(2)") } finally { committed.countDown() }
                assertEquals(listOf(1L), snapshot.get(5, TimeUnit.SECONDS))
                assertEquals(listOf(1L, 2L), db.query("SELECT n FROM t ORDER BY n") { it.long(0) })
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun separateBundledConnectionLockHonorsBudgetAndDoesNotCommitLate() = withFile { file ->
        DatabaseRegistry.openLocal(file, profile).use { db ->
            db.execute("CREATE TABLE t(n INTEGER)")
            SqliteConnectionFactory().open(file).use { external ->
                external.exec("PRAGMA busy_timeout = 0")
                external.exec("BEGIN IMMEDIATE")
                try {
                    val start = System.nanoTime()
                    val error = assertThrows(DatabaseBusyException::class.java) { db.execute("INSERT INTO t VALUES(1)") }
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                    Log.i(TAG, "case=bundled_lock budgetMs=${profile.lockWaitMillis} elapsedMs=$elapsed stage=${error.stage}")
                    assertEquals(DatabaseWait.SQLITE, error.stage)
                    assertTrue("elapsed=$elapsed", elapsed in 200..2000)
                } finally { external.exec("ROLLBACK") }
            }
            assertEquals(listOf(0L), db.query("SELECT count(*) FROM t") { it.long(0) })
            db.execute("INSERT INTO t VALUES(2)")
        }
    }

    @Test fun queueTimeoutCloseAndRollbackDoNotLeakConnections() = withFile { file ->
        val small = profile.copy(lockWaitMillis = 200)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val baseline = descriptorsFor(file)
            val db = DatabaseRegistry.openLocal(file, small)
            db.execute("CREATE TABLE t(n INTEGER)")
            val opened = descriptorsFor(file)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val first = pool.submit {
                db.transaction {
                    execute("INSERT INTO t VALUES(1)")
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    throw IllegalStateException("rollback")
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try {
                val writerStart = System.nanoTime()
                val writer = assertThrows(DatabaseBusyException::class.java) { db.execute("INSERT INTO t VALUES(2)") }
                val writerElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - writerStart)
                Log.i(TAG, "case=writer_queue budgetMs=${small.lockWaitMillis} elapsedMs=$writerElapsed stage=${writer.stage}")
                assertEquals(DatabaseWait.WRITER, writer.stage)
                val closeStart = System.nanoTime()
                val close = assertThrows(DatabaseBusyException::class.java) { db.close() }
                val closeElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStart)
                Log.i(TAG, "case=close_wait budgetMs=${small.lockWaitMillis} elapsedMs=$closeElapsed stage=${close.stage}")
                assertEquals(DatabaseWait.CLOSE, close.stage)
            } finally { release.countDown() }
            try { first.get(5, TimeUnit.SECONDS); fail("expected rollback") } catch (_: java.util.concurrent.ExecutionException) { }
            db.close()
            val after = descriptorsFor(file)
            Log.i(TAG, "case=fd databaseOnly=true before=$baseline opened=$opened after=$after")
            assertTrue(opened > baseline)
            assertEquals(baseline, after)
            DatabaseRegistry.openLocal(file, small).use { reopened ->
                assertEquals(listOf(0L), reopened.query("SELECT count(*) FROM t") { it.long(0) })
            }
            assertEquals(baseline, descriptorsFor(file))
        } finally { pool.shutdownNow() }
    }

    @Test fun readonlyAndLifecycleScopesAreEnforced() = withFile { file ->
        DatabaseRegistry.openLocal(file, profile).use { db ->
            db.execute("CREATE TABLE t(n INTEGER)")
            lateinit var snapshot: SqlReadOperations
            lateinit var transaction: SqlOperations
            db.transaction {
                transaction = this
                db.execute("INSERT INTO t VALUES(1)")
                assertEquals(listOf(1L), db.query("SELECT count(*) FROM t") { it.long(0) })
            }
            db.readSnapshot {
                snapshot = this
                assertThrows(MigrationException::class.java) { db.execute("INSERT INTO t VALUES(2)") }
                assertThrows(Exception::class.java) { query("INSERT INTO t VALUES(3) RETURNING n") { it.long(0) } }
                assertEquals(listOf(1L), query("PRAGMA foreign_keys") { it.long(0) })
            }
            assertThrows(MigrationException::class.java) { snapshot.query("SELECT 1") { it.long(0) } }
            assertThrows(MigrationException::class.java) { transaction.execute("INSERT INTO t VALUES(4)") }
        }
    }

    @Test fun bomExplainAndAutomaticRollbackCannotEscapeTransactionOwnership() = withFile { file ->
        DatabaseRegistry.openLocal(file, profile).use { db ->
            db.execute("CREATE TABLE t(n INTEGER UNIQUE)")
            db.execute("INSERT INTO t VALUES(1)")
            assertThrows(MigrationException::class.java) {
                db.transaction {
                    for (sql in listOf("\uFEFFCOMMIT", "EXPLAIN QUERY PLAN PRAGMA busy_timeout=999999")) {
                        assertThrows(MigrationException::class.java) { execute(sql) }
                    }
                    try { execute("INSERT OR ROLLBACK INTO t VALUES(1)") } catch (_: Exception) { }
                    execute("INSERT INTO t VALUES(2)")
                }
            }
            assertEquals(listOf(1L), db.query("SELECT n FROM t") { it.long(0) })
        }
    }

    private companion object { const val TAG = "DstuRegistryTest" }

    private fun withFile(block: (File) -> Unit) {
        val directory = File(context.cacheDir, "registry-${UUID.randomUUID()}")
        check(directory.mkdirs())
        try { block(File(directory, "test.db")) } finally { directory.deleteRecursively() }
    }

    private fun descriptorsFor(file: File): Int {
        val descriptors = checkNotNull(File("/proc/self/fd").listFiles()) { "cannot inspect process descriptors" }
        // Android may report a differently mounted path for the same app-private file.
        val targets = listOf("", "-wal", "-shm").mapNotNull { fileIdentity(file.path + it) }.toSet()
        return descriptors.count { fileIdentity(it.path) in targets }
    }

    private fun fileIdentity(path: String): Pair<Long, Long>? =
        try { Os.stat(path).let { it.st_dev to it.st_ino } }
        catch (_: android.system.ErrnoException) { null }
}
