package com.deepstudent.platform.database

import com.deepstudent.platform.database.internal.FrozenCatalogLoader
import com.deepstudent.platform.database.internal.MigrationCommitKind
import com.deepstudent.platform.database.internal.MigrationCommitObserver
import com.deepstudent.platform.database.internal.SqliteConnectionFactory
import com.deepstudent.platform.database.internal.SqliteSession
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationMaintenanceTest {
    private val root = Files.createTempDirectory("s2-03-maintenance").toFile()
    private val repository = File(System.getProperty("user.dir")).let {
        if (it.resolve("config/schema/final-lock.json").isFile) it else it.resolve("../..")
    }
    private val source = MigrationSource { repository.resolve(it).readBytes() }

    @After fun cleanup() { MigrationCommitObserver.onCommit = null; root.deleteRecursively() }

    @Test fun sourceFailureIsSafeAndRetryUsesTheSameFrozenOpenPath() {
        val secret = "secret-SQL-path-credential-7e2d"
        val fail = AtomicBoolean(true)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val file = root.resolve("vfs.db")
        val events = mutableListOf<MigrationLogEvent>()
        val maintenance = MigrationMaintenance(file, SharedDatabase.VFS, MigrationSource {
            reads.incrementAndGet()
            if (fail.get()) throw IllegalStateException(secret) else source.read(it)
        }, migrationLog = MigrationLog { events += it })
        assertEquals(MigrationState.IDLE, maintenance.query().state)
        val thrown = assertThrows(MigrationMaintenanceException::class.java) { maintenance.open() }
        assertEquals(MigrationState.FAILED, thrown.status.state)
        assertEquals(MigrationStage.SOURCE, thrown.status.stage)
        assertEquals(MigrationDiagnostic.SOURCE_INVALID, thrown.status.failure?.diagnostic)
        assertNull(thrown.status.committedVersion)
        assertNull(thrown.status.targetVersion)
        assertNull(thrown.cause)
        assertFalse(file.exists())
        assertFalse("${thrown.message} ${thrown.status} $events".contains(secret))
        val secondStartup = assertThrows(MigrationMaintenanceException::class.java) { maintenance.open() }
        assertEquals(thrown.status, secondStartup.status)
        assertEquals(1, reads.get()) // A second startup caller must not silently retry.
        assertEquals(MigrationState.FAILED, maintenance.retry().state)
        assertEquals(2, reads.get())
        fail.set(false)
        val ready = maintenance.retry()
        assertEquals(MigrationState.READY, ready.state)
        assertEquals(20261120, ready.committedVersion)
        assertEquals(20261120, ready.targetVersion)
        maintenance.open().use { assertEquals(0, it.migrationReport?.appliedCount) }
    }

    @Test fun failedNextMigrationReportsLastCommittedAndRetryNeverReplaysIt() {
        val file = root.resolve("chat.db")
        val catalog = FrozenCatalogLoader.load(source).forDatabase(SharedDatabase.CHAT_V2)
        val first = catalog[0].version
        val second = catalog[1].version
        var blocker: SqliteSession? = null
        val committed = mutableListOf<Int>()
        MigrationCommitObserver.onCommit = { event ->
            if (event.database == SharedDatabase.CHAT_V2 && event.kind == MigrationCommitKind.MIGRATION) {
                committed += event.version
                if (event.version == first && blocker == null) {
                    blocker = SqliteConnectionFactory().open(file).also { it.exec("BEGIN IMMEDIATE") }
                }
            }
        }
        val maintenance = MigrationMaintenance(file, SharedDatabase.CHAT_V2, source, DatabaseProfile(lockWaitMillis = 250))
        try {
            val error = assertThrows(MigrationMaintenanceException::class.java) { maintenance.open() }
            assertEquals(MigrationStage.MIGRATION, error.status.stage)
            assertEquals(first, error.status.committedVersion)
            assertEquals(second, error.status.failure?.version)
            assertEquals(catalog.last().version, error.status.targetVersion)
            assertEquals(MigrationDiagnostic.DATABASE_FAILED, error.status.failure?.diagnostic)
            val appliedOn = blocker!!.queryText("SELECT applied_on FROM refinery_schema_history WHERE version=$first")
            blocker!!.exec("ROLLBACK")
            blocker!!.close()
            blocker = null
            // Do not acquire another blocker during retry.
            MigrationCommitObserver.onCommit = { event ->
                if (event.database == SharedDatabase.CHAT_V2 && event.kind == MigrationCommitKind.MIGRATION) committed += event.version
            }
            assertEquals(MigrationState.READY, maintenance.retry().state)
            assertEquals(catalog.map { it.version }, committed)
            maintenance.open().use { db ->
                assertEquals(0, db.migrationReport?.appliedCount)
                assertEquals(listOf(appliedOn), db.query("SELECT applied_on FROM refinery_schema_history WHERE version=$first") { it.text(0) })
            }
        } finally {
            blocker?.let { runCatching { it.exec("ROLLBACK"); it.close() } }
        }
    }

    @Test fun invalidHistoryIsNotReportedAsAValidCommittedVersion() {
        val file = root.resolve("mistakes.db")
        DatabaseRegistry.openShared(file, SharedDatabase.MISTAKES, source).close()
        DatabaseRegistry.openLocal(file).use { it.execute("UPDATE refinery_schema_history SET checksum='forged' WHERE version=(SELECT MIN(version) FROM refinery_schema_history)") }
        val maintenance = MigrationMaintenance(file, SharedDatabase.MISTAKES, source)
        val error = assertThrows(MigrationMaintenanceException::class.java) { maintenance.open() }
        assertEquals(MigrationStage.INSPECT, error.status.stage)
        assertEquals(MigrationDiagnostic.HISTORY_DRIFT, error.status.failure?.diagnostic)
        assertEquals(FrozenCatalogLoader.load(source).forDatabase(SharedDatabase.MISTAKES).first().version, error.status.failure?.version)
        assertNull(error.status.committedVersion)
        assertEquals(MigrationState.FAILED, maintenance.retry().state)
    }

    @Test fun concurrentAttemptsAreSerializedAndQueryDoesNotStartAnOpen() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val guarded = MigrationSource { path ->
            if (path == "config/schema/final-lock.json" && calls.incrementAndGet() == 1) {
                inside.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            source.read(path)
        }
        val maintenance = MigrationMaintenance(root.resolve("llm.db"), SharedDatabase.LLM_USAGE, guarded)
        val pool = Executors.newFixedThreadPool(2)
        try {
            assertEquals(MigrationState.IDLE, maintenance.query().state)
            assertEquals(0, calls.get())
            val first = pool.submit<MigrationStatus> { maintenance.retry() }
            assertTrue(inside.await(10, TimeUnit.SECONDS))
            assertEquals(MigrationState.RUNNING, maintenance.query().state)
            val second = pool.submit<MigrationStatus> { maintenance.retry() }
            Thread.sleep(100)
            assertEquals(1, calls.get())
            release.countDown()
            assertEquals(MigrationState.READY, first.get(30, TimeUnit.SECONDS).state)
            assertEquals(MigrationState.READY, second.get(30, TimeUnit.SECONDS).state)
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
