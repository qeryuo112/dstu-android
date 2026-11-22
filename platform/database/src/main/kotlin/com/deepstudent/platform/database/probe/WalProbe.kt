package com.deepstudent.platform.database.probe

import com.deepstudent.platform.database.DatabaseError
import com.deepstudent.platform.database.DatabaseErrorKind
import com.deepstudent.platform.database.ProbeId
import com.deepstudent.platform.database.ProbeResult
import com.deepstudent.platform.database.internal.ProbeContext
import com.deepstudent.platform.database.internal.ProbeFamily
import com.deepstudent.platform.database.internal.ProbeAssertionFailure
import com.deepstudent.platform.database.internal.SqliteSession
import com.deepstudent.platform.database.internal.captureDatabaseError
import com.deepstudent.platform.database.internal.expectEquals
import com.deepstudent.platform.database.internal.expectTrue

internal class WalProbe : ProbeFamily {
    override fun run(context: ProbeContext): List<ProbeResult> = listOf(
        context.capture(ProbeId.WAL_SNAPSHOT) { snapshot(context) },
        context.capture(ProbeId.WAL_BUSY_TIMEOUT) { busyTimeout(context) },
    )

    private fun snapshot(context: ProbeContext): Map<String, String> {
        val fileName = "wal-snapshot.db"
        seed(context, fileName)
        return context.withSession(fileName) { first ->
            context.withSession(fileName) { second ->
                first.exec("begin")
                val before = count(first)
                second.exec("insert into wal_items(value) values('writer')")
                val writerCount = count(second)
                val snapshotCount = count(first)
                first.exec("commit")
                val afterCommit = count(first)

                expectEquals(snapshotCount, before, "WAL read snapshot")
                expectEquals(writerCount, before + 1, "WAL writer visibility")
                expectEquals(afterCommit, before + 1, "WAL snapshot after commit")
                mapOf(
                    "journalMode" to first.queryText("pragma journal_mode"),
                    "snapshotBefore" to before.toString(),
                    "snapshotAfterWriter" to snapshotCount.toString(),
                    "writerCount" to writerCount.toString(),
                    "afterCommit" to afterCommit.toString(),
                )
            }
        }
    }

    private fun busyTimeout(context: ProbeContext): Map<String, String> {
        val fileName = "wal-busy.db"
        seed(context, fileName)
        return context.withSession(fileName) { first ->
            context.withSession(fileName) { second ->
                busyResult(context, first, holdWriteLockAndMeasure(first, second))
            }
        }
    }

    /** Holds the writer lock, measures the blocked writer, and always rolls back. */
    private fun holdWriteLockAndMeasure(
        first: SqliteSession,
        second: SqliteSession,
    ): BusyMeasurement {
        first.exec("pragma busy_timeout=$BUSY_TIMEOUT_MILLIS")
        second.exec("pragma busy_timeout=$BUSY_TIMEOUT_MILLIS")
        first.exec("begin immediate")
        first.exec("insert into wal_items(value) values('lock-holder')")
        val started = System.nanoTime()
        return try {
            val error = captureDatabaseError {
                second.exec("insert into wal_items(value) values('blocked')")
            } ?: throw ProbeAssertionFailure("second writer must observe a busy lock")
            BusyMeasurement(error, (System.nanoTime() - started) / NANOS_PER_MILLI)
        } finally {
            first.exec("rollback")
        }
    }

    /** Validates the lock budget and maps only fixed, privacy-safe evidence. */
    private fun busyResult(
        context: ProbeContext,
        first: SqliteSession,
        measurement: BusyMeasurement,
    ): Map<String, String> {
        val error = measurement.error
        context.report(error)
        expectEquals(error.kind, DatabaseErrorKind.BUSY, "busy error kind")
        expectTrue(
            error.sqliteCode in BUSY_CODES,
            "busy error code must be SQLITE_BUSY or SQLITE_LOCKED, actual ${error.sqliteCode}",
        )
        expectTrue(
            measurement.busyMillis >= MIN_BUSY_MILLIS,
            "busy timeout returned too early: ${measurement.busyMillis}ms",
        )
        expectTrue(
            measurement.busyMillis <= MAX_BUSY_MILLIS,
            "busy timeout exceeded budget: ${measurement.busyMillis}ms",
        )
        return mapOf(
            "journalMode" to first.queryText("pragma journal_mode"),
            "busyTimeoutMillis" to BUSY_TIMEOUT_MILLIS.toString(),
            "busyMillis" to measurement.busyMillis.toString(),
            "busyCode" to error.sqliteCode.toString(),
            "busyKind" to error.kind.name,
            "busyDiagnostic" to error.diagnostic.name,
        )
    }

    private fun seed(context: ProbeContext, fileName: String) {
        context.withSession(fileName) { session ->
            expectEquals(
                session.queryText("pragma journal_mode=wal").lowercase(),
                "wal",
                "WAL journal mode",
            )
            session.exec("create table wal_items(id integer primary key, value text not null)")
            session.exec("insert into wal_items(value) values('seed')")
        }
    }

    private fun count(session: SqliteSession): Long =
        session.queryLong("select count(*) from wal_items")

    private data class BusyMeasurement(
        val error: DatabaseError,
        val busyMillis: Long,
    )

    private companion object {
        const val BUSY_TIMEOUT_MILLIS = 250
        const val MIN_BUSY_MILLIS = 100L
        const val MAX_BUSY_MILLIS = 5_000L
        const val NANOS_PER_MILLI = 1_000_000L
        val BUSY_CODES = setOf(5, 6)
    }
}
