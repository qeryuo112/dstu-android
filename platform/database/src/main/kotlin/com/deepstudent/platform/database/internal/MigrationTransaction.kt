package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.MigrationDiagnostic
import com.deepstudent.platform.database.MigrationException
import com.deepstudent.platform.database.SharedDatabase
import java.time.Instant

/** Caller holds the write transaction; SQL and its refinery row commit or roll back together. */
internal fun applyStatements(
    session: SqliteSession, database: SharedDatabase, migration: FrozenMigration, statements: List<String>, rebuild: Boolean,
) {
    statements.forEach(session::exec)
    if (rebuild && session.queryAll("PRAGMA foreign_key_check") { true }.isNotEmpty()) {
        throw MigrationException(MigrationDiagnostic.MIGRATION_FAILED, database, migration.version, "migration", migration.name)
    }
    session.execBind("INSERT INTO refinery_schema_history(version,name,applied_on,checksum) VALUES(?,?,?,?)") {
        it.bindLong(1, migration.version.toLong())
        it.bindText(2, migration.name)
        it.bindText(3, Instant.now().toString())
        it.bindText(4, migration.checksum)
    }
}

/** FK enforcement must be switched before BEGIN; validate rebuilt references before COMMIT. */
internal fun <T> migrationWrite(
    session: SqliteSession, rebuild: Boolean, afterCommit: (T) -> Unit = {}, block: () -> T,
): T {
    if (rebuild) session.exec("PRAGMA foreign_keys = OFF")
    try {
        return migrationTransaction(session, "BEGIN IMMEDIATE", afterCommit, block)
    } finally {
        if (rebuild) session.exec("PRAGMA foreign_keys = ON")
    }
}

internal fun <T> migrationTransaction(
    session: SqliteSession, begin: String, afterCommit: (T) -> Unit = {}, block: () -> T,
): T {
    session.exec(begin)
    val result: T
    try {
        result = block()
        session.exec("COMMIT")
    } catch (failure: Throwable) {
        try { if (session.inTransaction()) session.exec("ROLLBACK") } catch (_: Exception) { }
        throw failure
    }
    // Outside the rollback handler and immediately after the successful COMMIT.
    afterCommit(result)
    return result
}
