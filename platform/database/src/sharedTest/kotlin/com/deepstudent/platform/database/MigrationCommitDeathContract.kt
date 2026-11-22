package com.deepstudent.platform.database

import com.deepstudent.platform.database.internal.MigrationCommit
import com.deepstudent.platform.database.internal.MigrationCommitKind
import com.deepstudent.platform.database.internal.MigrationCommitObserver
import com.deepstudent.platform.database.internal.SqliteConnectionFactory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull

/** Each call is made by a different OS process. Only the first desktop call starts with an external fixture. */
internal class MigrationCommitDeathContract(
    private val root: File, private val database: SharedDatabase, private val source: MigrationSource,
    fixture: ByteArray?, private val pid: Long,
) {
    private val file = root.resolve("database.db")
    private val marker = root.resolve("commit")
    private val evidence = MigrationCheckpointEvidence(file, database, source, fixture)

    fun advance(before: Int, terminate: () -> Nothing): Nothing {
        require(before in evidence.initialCount until evidence.history.size)
        val previous = if (before == evidence.initialCount) null else MigrationCommitKind.MIGRATION
        val snapshot = verifyRecovered(before, previous)
        assertNull("governance must not be written ahead of head", snapshot.governance)
        return openAndTerminate(snapshot, before + 1, MigrationCommitKind.MIGRATION, terminate)
    }

    fun migrateLegacyHead(terminate: () -> Nothing): Nothing {
        val snapshot = verifyRecovered(evidence.history.size, MigrationCommitKind.MIGRATION)
        evidence.requireFinalGovernance(snapshot)
        // Simulate an existing legacy head, only in our private instrumentation/JVM database.
        SqliteConnectionFactory().open(file).use { it.exec("DROP TABLE __governance_schema_fingerprints") }
        assertEquals(snapshot.copy(governance = null), evidence.observe(evidence.history.size))
        return openAndTerminate(snapshot, evidence.history.size, MigrationCommitKind.GOVERNANCE, terminate)
    }

    fun verifyHead(kind: MigrationCommitKind) {
        val count = evidence.history.size
        val before = verifyRecovered(count, kind)
        evidence.requireFinalGovernance(before)
        withCommitObserver({ error("reopen made an unexpected durable commit: $it") }) {
            DatabaseRegistry.openShared(file, database, source).use { db ->
                val report = checkNotNull(db.migrationReport)
                assertEquals(database, report.database)
                assertEquals(evidence.version, report.fromVersion)
                assertEquals(evidence.version, report.toVersion)
                assertEquals(0, report.appliedCount)
                assertEquals(evidence.fingerprint, report.fingerprint)
            }
        }
        // Includes all applied_on values, canonical schema and verified_at, not just the target version.
        assertEquals(before, evidence.observe(count))
        writeEvidence(root.resolve("verified"), listOf(database.id, kind.name, count, evidence.version, pid,
            before.ledgerDigest(), evidence.fingerprint, "restartApplied=0", "governanceUnchanged=true"))
    }

    private fun verifyRecovered(count: Int, kind: MigrationCommitKind?): CheckpointSnapshot {
        val observed = evidence.observe(count)
        if (kind == null) {
            assertFalse("test input already has a commit marker", marker.exists())
        } else {
            val last = MigrationDeathMarker.read(marker)
            assertNotEquals("reopen must be a different process", last.pid, pid)
            assertEquals(database.id, last.database)
            assertEquals(kind.name, last.kind)
            assertEquals(count, last.count)
            assertEquals(last.version, observed.ledger.last().entry.version)
            assertEquals(if (kind == MigrationCommitKind.MIGRATION) count - 1 else count, last.priorCount)
            assertEquals("recovery rewrote a previously committed ledger row", last.priorDigest, observed.ledgerDigest(last.priorCount))
        }
        return observed
    }

    private fun openAndTerminate(
        before: CheckpointSnapshot, count: Int, kind: MigrationCommitKind, terminate: () -> Nothing,
    ): Nothing {
        val version = evidence.history[count - 1].version
        val priorDigest = before.ledgerDigest()
        withCommitObserver({ commit ->
            assertEquals(MigrationCommit(database, version, kind), commit)
            MigrationDeathMarker(kind.name, database.id, count, version, pid, before.ledger.size, priorDigest).write(marker)
            // Never allow a SQLite close, an exception unwind, or the next migration in this process.
            terminate()
        }) {
            DatabaseRegistry.openShared(file, database, source).close()
        }
        error("production open returned without reaching its expected commit point")
    }
}

private fun <T> withCommitObserver(observer: (MigrationCommit) -> Unit, operation: () -> T): T {
    check(MigrationCommitObserver.onCommit == null)
    MigrationCommitObserver.onCommit = observer
    try {
        return operation()
    } finally {
        MigrationCommitObserver.onCommit = null
    }
}
