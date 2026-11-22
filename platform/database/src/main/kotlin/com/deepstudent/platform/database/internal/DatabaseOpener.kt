package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.DatabaseBusyException
import com.deepstudent.platform.database.DatabaseErrorKind
import com.deepstudent.platform.database.DatabaseProfile
import com.deepstudent.platform.database.DatabaseWait
import com.deepstudent.platform.database.MigrationDiagnostic
import com.deepstudent.platform.database.MigrationException
import com.deepstudent.platform.database.MigrationLog
import com.deepstudent.platform.database.MigrationLogEvent
import com.deepstudent.platform.database.MigrationLogDetails
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.MigrationReport
import com.deepstudent.platform.database.MigrationStage
import com.deepstudent.platform.database.MigrationStatus
import java.io.File
import java.util.UUID

internal data class OpenedDatabase(val session: SqliteSession, val report: MigrationReport?)

internal object DatabaseOpener {
    fun openShared(file: File, database: SharedDatabase, source: MigrationSource, profile: DatabaseProfile, log: MigrationLog): OpenedDatabase {
        val correlation = UUID.randomUUID().toString()
        val start = System.nanoTime()
        var session: SqliteSession? = null
        val tracker = MigrationStatusTracker(database)
        try {
            val catalog = FrozenCatalogLoader.load(source)
            val schema = catalog.schemas.getValue(database)
            tracker.target(schema.version)
            tracker.stage(MigrationStage.OPEN)
            session = SqliteConnectionFactory().open(file)
            prepareWriter(session, profile)
            val report = MigrationRunner(session, database, catalog.forDatabase(database), schema, tracker).run()
            tracker.stage(MigrationStage.WAL)
            enableWal(session, profile, database)
            emit(log, correlation, database, start, report, null, tracker.ready(report))
            return OpenedDatabase(session, report)
        } catch (failure: Throwable) {
            closeAfterFailure(session)
            if (failure !is Exception) throw failure
            val sanitized = sanitizeFailure(failure, database)
            emit(log, correlation, database, start, null, sanitized, tracker.failed(sanitized))
            if (failure is DatabaseFailure && failure.error.kind == DatabaseErrorKind.BUSY) {
                throw DatabaseBusyException(DatabaseWait.SQLITE)
            }
            throw sanitized
        }
    }

    /** Revalidate a second shared lease with the existing S1 runner, never a second write connection. */
    fun revalidateShared(
        engine: DatabaseEngine, database: SharedDatabase, source: MigrationSource, log: MigrationLog,
    ): MigrationReport {
        val correlation = UUID.randomUUID().toString()
        val start = System.nanoTime()
        val tracker = MigrationStatusTracker(database)
        try {
            val catalog = FrozenCatalogLoader.load(source)
            tracker.target(catalog.schemas.getValue(database).version)
            tracker.stage(MigrationStage.INSPECT)
            val report = engine.revalidate(database, catalog)
            emit(log, correlation, database, start, report, null, tracker.ready(report))
            return report
        } catch (failure: Exception) {
            val sanitized = sanitizeFailure(failure, database)
            emit(log, correlation, database, start, null, sanitized, tracker.failed(sanitized))
            if (failure is DatabaseBusyException) throw failure
            throw sanitized
        }
    }

    fun openLocal(file: File, profile: DatabaseProfile): OpenedDatabase {
        var session: SqliteSession? = null
        try {
            session = SqliteConnectionFactory().open(file)
            configureWriter(session, profile, null)
            return OpenedDatabase(session, null)
        } catch (failure: Throwable) {
            closeAfterFailure(session)
            if (failure !is Exception) throw failure
            if (failure is DatabaseFailure && failure.error.kind == DatabaseErrorKind.BUSY) {
                throw DatabaseBusyException(DatabaseWait.SQLITE)
            }
            throw sanitizeFailure(failure, null)
        }
    }

    private fun emit(
        log: MigrationLog,
        correlation: String,
        database: SharedDatabase,
        start: Long,
        report: MigrationReport?,
        failure: MigrationException?,
        status: MigrationStatus,
    ) {
        val elapsed = (System.nanoTime() - start).coerceAtLeast(0L) / 1_000_000L
        runCatching {
            log.onEvent(MigrationLogEvent(
                correlationId = correlation, database = database, phase = "open-migrate",
                status = if (failure == null) "PASSED" else "FAILED", durationMillis = elapsed,
                version = report?.toVersion ?: failure?.version, diagnostic = failure?.diagnostic,
                details = MigrationLogDetails(report, failure?.objectType, failure?.objectName, status),
            ))
        }
    }
}

private fun configureWriter(session: SqliteSession, profile: DatabaseProfile, database: SharedDatabase?) {
    prepareWriter(session, profile)
    enableWal(session, profile, database)
}

private fun prepareWriter(session: SqliteSession, profile: DatabaseProfile) {
    session.exec("PRAGMA busy_timeout = ${profile.lockWaitMillis}")
    session.exec("PRAGMA foreign_keys = ON")
}

private fun enableWal(session: SqliteSession, profile: DatabaseProfile, database: SharedDatabase?) {
    if (session.queryText("PRAGMA journal_mode = WAL").lowercase() != "wal") {
        throw MigrationException(MigrationDiagnostic.DATABASE_FAILED, database)
    }
    session.exec("PRAGMA synchronous = ${profile.synchronous.name}")
}

@Suppress("TooGenericExceptionCaught")
private fun closeAfterFailure(session: SqliteSession?) {
    try { session?.close() } catch (_: Exception) { }
}

internal fun sanitizeFailure(failure: Exception, database: SharedDatabase?): MigrationException = when (failure) {
    is MigrationException -> failure
    is DatabaseFailure -> MigrationException(
        MigrationDiagnostic.DATABASE_FAILED, database, causeClass = "DatabaseOperationFailure",
    )
    else -> MigrationException(MigrationDiagnostic.MIGRATION_FAILED, database, causeClass = "DatabaseOperationFailure")
}
