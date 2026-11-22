package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.DatabaseErrorKind
import com.deepstudent.platform.database.MigrationDiagnostic
import com.deepstudent.platform.database.MigrationException
import com.deepstudent.platform.database.MigrationReport
import com.deepstudent.platform.database.MigrationStage
import com.deepstudent.platform.database.SharedDatabase

internal class MigrationRunner(
    private val session: SqliteSession,
    private val database: SharedDatabase,
    private val migrations: List<FrozenMigration>,
    private val schema: FrozenSchema,
    private val tracker: MigrationStatusTracker? = null,
) {
    private val governance = GovernanceStore(session, database)

    fun run(): MigrationReport {
        tracker?.stage(MigrationStage.INSPECT)
        session.exec("PRAGMA foreign_keys = ON")
        // No CREATE, ALTER or timestamp update until the existing snapshot has passed all checks.
        val observed = dataVersion()
        val initial = migrationTransaction(session, "BEGIN") { inspect() }
        tracker?.inspected(initial.version)
        var progress = Progress(initial, observed, 0)
        while (progress.state.history.size < migrations.size) progress = applyNext(progress)
        if (!progress.state.governed) initializeGovernance()
        return MigrationReport(database, initial.version, schema.version, progress.applied, schema.fingerprint)
    }

    private fun applyNext(progress: Progress): Progress {
        val next = migrations[progress.state.history.size]
        tracker?.applying(next.version)
        val statements = splitStatements(next.sql)
        val rebuild = statements.any(::isDropTable)
        try {
            val result = migrationWrite(session, rebuild, afterCommit = { committed ->
                if (committed.applied > progress.applied) {
                    MigrationCommitObserver.onCommit?.invoke(MigrationCommit(database, next.version, MigrationCommitKind.MIGRATION))
                    tracker?.committed(next.version)
                }
            }) { applyLocked(progress, next, statements, rebuild) }
            tracker?.inspected(result.state.version)
            return result
        } catch (failure: Exception) {
            if (failure is DatabaseFailure && failure.error.kind == DatabaseErrorKind.BUSY) throw failure
            if (failure is MigrationException) throw failure
            throw MigrationException(
                MigrationDiagnostic.MIGRATION_FAILED, database, next.version,
                "migration", next.name, "MigrationExecutionFailure",
            )
        }
    }

    private fun applyLocked(progress: Progress, next: FrozenMigration, statements: List<String>, rebuild: Boolean): Progress {
        val now = dataVersion()
        val history = readHistory()
        // BEGIN IMMEDIATE serializes other writers; re-check a snapshot changed by another connection.
        val state = if (now != progress.observed || history != progress.state.history) inspect(history) else progress.state
        if (state.history.size >= migrations.size || migrations[state.history.size] != next) return Progress(state, now, progress.applied)
        ensureHistory()
        applyStatements(session, database, next, statements, rebuild)
        val updated = state.history + HistoryEntry(next.version.toLong(), next.name, next.checksum, true)
        if (updated.size == migrations.size) recordHeadGovernance()
        return Progress(Inspection(updated, next.version, true), now, progress.applied + 1)
    }

    private fun initializeGovernance() {
        // Legacy head databases can lack governance. Revalidate under the write lock before inserting once.
        tracker?.stage(MigrationStage.GOVERNANCE)
        migrationWrite(session, false, afterCommit = { recorded ->
            if (recorded) {
                MigrationCommitObserver.onCommit?.invoke(MigrationCommit(database, schema.version, MigrationCommitKind.GOVERNANCE))
            }
        }) {
            val current = inspect()
            if (current.history.size != migrations.size) throw MigrationException(MigrationDiagnostic.HISTORY_DRIFT, database)
            if (!current.governed) { recordHeadGovernance(); true } else false
        }
    }

    private fun recordHeadGovernance() {
        val actual = verifySchema()
        if (!governance.verify(schema.version, actual)) {
            governance.ensureTable()
            governance.store(schema.version, actual)
        }
    }

    /** The S2.01 live writer already owns a transaction; revalidation must never migrate or write. */
    fun verifyCurrent(): MigrationReport {
        val state = inspect()
        if (state.history.size != migrations.size) throw MigrationException(MigrationDiagnostic.HISTORY_DRIFT, database)
        return MigrationReport(database, state.version, schema.version, 0, schema.fingerprint)
    }

    private fun inspect(history: List<HistoryEntry> = readHistory()): Inspection {
        validateHistory(history)
        governance.validateStructure()
        if (history.isEmpty()) rejectUntrackedManagedObjects()
        val version = history.lastOrNull()?.version?.toInt() ?: 0
        val governed = when {
            version == schema.version -> governance.verify(version, verifySchema())
            version != 0 -> verifyPreviousSchema(history.size)
            else -> false
        }
        return Inspection(history, version, governed)
    }

    private fun dataVersion(): Long = session.queryLong("PRAGMA data_version")

    private fun readHistory(): List<HistoryEntry> = HistoryReader(session, database).read()

    private fun rejectUntrackedManagedObjects() {
        val present = session.queryTexts("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").toSet()
        val frozen = schema.scope.tables.map { "table" to it } + schema.scope.indexes.map { "index" to it } +
            schema.scope.triggers.map { "trigger" to it }
        val existing = frozen.firstOrNull { it.second in present } ?: return
        throw MigrationException(MigrationDiagnostic.HISTORY_DRIFT, database, objectType = existing.first, objectName = existing.second)
    }

    private fun ensureHistory() {
        session.exec(
            "CREATE TABLE IF NOT EXISTS refinery_schema_history(" +
                "version int4 PRIMARY KEY, name VARCHAR(255), applied_on VARCHAR(255), checksum VARCHAR(255))",
        )
    }

    private fun validateHistory(history: List<HistoryEntry>) {
        if (history.size > migrations.size) throw MigrationException(MigrationDiagnostic.HISTORY_DRIFT, database)
        history.forEachIndexed { index, row ->
            val expected = migrations[index]
            if (!row.typesValid || row.version != expected.version.toLong() || row.name != expected.name || row.checksum != expected.checksum) {
                throw MigrationException(MigrationDiagnostic.HISTORY_DRIFT, database, expected.version, "migration", expected.name)
            }
        }
        if (migrations.lastOrNull()?.version != schema.version) fail(MigrationDiagnostic.SOURCE_INVALID)
    }

    private fun verifySchema(): CanonicalSchema {
        val actual = SchemaFingerprint(session).compute(schema.scope)
        if (actual.hash == schema.fingerprint) return actual
        val expected = scratchFingerprint()
        val difference = if (expected.hash == schema.fingerprint) firstSchemaDifference(expected, actual, schema.scope) else null
        throw MigrationException(MigrationDiagnostic.SCHEMA_DRIFT, database, schema.version, difference?.first, difference?.second)
    }

    private fun scratchFingerprint(): CanonicalSchema = SqliteConnectionFactory().openMemory().use { scratch ->
        migrations.forEach { migration -> splitStatements(migration.sql).forEach(scratch::exec) }
        SchemaFingerprint(scratch).compute(schema.scope)
    }

    private fun verifyPreviousSchema(count: Int): Boolean = SqliteConnectionFactory().openMemory().use { scratch ->
        migrations.take(count).forEach { migration -> splitStatements(migration.sql).forEach(scratch::exec) }
        val scope = scratch.schemaScope()
        val expected = SchemaFingerprint(scratch).compute(scope)
        val actual = SchemaFingerprint(session).compute(scope)
        if (expected.hash != actual.hash) {
            val difference = firstSchemaDifference(expected, actual, scope)
            throw MigrationException(
                MigrationDiagnostic.SCHEMA_DRIFT, database, migrations[count - 1].version, difference?.first, difference?.second,
            )
        }
        governance.verify(migrations[count - 1].version, actual)
    }
}

private data class Inspection(val history: List<HistoryEntry>, val version: Int, val governed: Boolean)

private data class Progress(val state: Inspection, val observed: Long, val applied: Int)
