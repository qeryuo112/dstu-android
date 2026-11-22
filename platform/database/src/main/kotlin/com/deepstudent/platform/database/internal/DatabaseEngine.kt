package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.DatabaseProfile
import com.deepstudent.platform.database.DatabaseWait
import com.deepstudent.platform.database.MigrationDiagnostic
import com.deepstudent.platform.database.MigrationException
import com.deepstudent.platform.database.MigrationReport
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.platform.database.SqlValue
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal class DatabaseEngine(
    private val file: File,
    private val profile: DatabaseProfile,
    private val writer: SqliteSession,
    val report: MigrationReport?,
    private val identity: DatabaseFileIdentity? = null,
) : AutoCloseable {
    private val writerLock = ReentrantLock(true)
    private val readers = Semaphore(profile.maxReaders, true)
    private val callback = ThreadLocal<ActiveCallback>()
    @Volatile private var poisoned = false

    fun checkNotReentrant() {
        if (callback.get() != null) throw MigrationException(MigrationDiagnostic.DATABASE_FAILED)
    }

    fun <T> write(owner: Any, block: SqlOperations.() -> T): T {
        checkNotReentrant()
        val budget = DatabaseWaitBudget(profile.lockWaitMillis)
        budget.measured { interruptible({ writerLock.tryLock(budget.nanos(), TimeUnit.NANOSECONDS) }, DatabaseWait.WRITER) }
        try {
            checkHealthy()
            return mapBusy { runWrite(owner, budget, block) }
        } finally { writerLock.unlock() }
    }

    private fun <T> runWrite(owner: Any, budget: DatabaseWaitBudget, block: SqlOperations.() -> T): T {
        var scoped: ScopedWriter? = null
        try {
            busyTimeout(writer, budget)
            budget.measured { writer.exec("BEGIN IMMEDIATE") }
            scoped = ScopedWriter(BudgetedOperations(StatementOperations(writer) {
                checkWriterTransaction()
                busyTimeout(writer, budget)
            }, budget))
            callback.set(ActiveCallback(owner, scoped, scoped))
            val result = scoped.block()
            callback.remove()
            checkWriterTransaction()
            busyTimeout(writer, budget)
            budget.measured { writer.exec("COMMIT") }
            return result
        } catch (failure: Throwable) {
            rollbackWriter(failure)
            throw failure
        } finally {
            callback.remove()
            scoped?.active = false
        }
    }

    private fun checkWriterTransaction() {
        if (!writer.inTransaction()) throw MigrationException(MigrationDiagnostic.DATABASE_FAILED)
    }

    private fun checkHealthy() {
        if (poisoned) throw MigrationException(MigrationDiagnostic.DATABASE_FAILED)
        identity?.verify()
    }

    private fun rollbackWriter(original: Throwable) {
        try {
            if (!writer.inTransaction()) return // SQLite may already have auto-rolled back.
            try { writer.exec("ROLLBACK") } catch (failure: Throwable) {
                original.addSuppressed(MigrationException(MigrationDiagnostic.DATABASE_FAILED, causeClass = failure.javaClass.name))
            }
            if (writer.inTransaction()) poisoned = true
        } catch (failure: Throwable) {
            poisoned = true
            original.addSuppressed(MigrationException(MigrationDiagnostic.DATABASE_FAILED, causeClass = failure.javaClass.name))
        }
    }

    /** Reuses the S1 runner on the sole writer; a live owner must already be at the migration head. */
    fun revalidate(database: SharedDatabase, catalog: FrozenCatalog): MigrationReport = write(this) {
        MigrationRunner(writer, database, catalog.forDatabase(database), catalog.schemas.getValue(database)).verifyCurrent()
    }

    fun <T> read(owner: Any, block: SqlReadOperations.() -> T): T {
        checkNotReentrant()
        checkHealthy()
        val budget = DatabaseWaitBudget(profile.lockWaitMillis)
        budget.measured { interruptible({ readers.tryAcquire(budget.nanos(), TimeUnit.NANOSECONDS) }, DatabaseWait.READER) }
        try {
            checkHealthy()
            return mapBusy { readSession(owner, budget, block) }
        } finally { readers.release() }
    }

    private fun <T> readSession(owner: Any, budget: DatabaseWaitBudget, block: SqlReadOperations.() -> T): T {
        SqliteConnectionFactory().openReadOnly(file).use { reader ->
            identity?.verify()
            reader.exec("PRAGMA foreign_keys = ON")
            reader.exec("PRAGMA synchronous = ${profile.synchronous.name}")
            reader.exec("PRAGMA query_only = ON")
            busyTimeout(reader, budget)
            budget.measured { reader.exec("BEGIN") }
            // Force SQLite to acquire the WAL read mark before invoking user code.
            budget.measured { reader.queryLong("SELECT count(*) FROM sqlite_schema") }
            val scoped = ScopedReader(BudgetedOperations(StatementOperations(reader) { busyTimeout(reader, budget) }, budget))
            try {
                callback.set(ActiveCallback(owner, scoped, null))
                return scoped.block()
            } finally {
                scoped.active = false
                callback.remove()
                if (reader.inTransaction()) reader.exec("ROLLBACK")
            }
        }
    }

    /** Preserve S1 same-handle reentrant query/execute inside a transaction on its scoped connection. */
    fun active(owner: Any): ActiveCallback? {
        val current = callback.get() ?: return null
        if (current.owner !== owner) throw MigrationException(MigrationDiagnostic.DATABASE_FAILED)
        return current
    }

    override fun close() = writer.close()
}

private fun busyTimeout(session: SqliteSession, budget: DatabaseWaitBudget) {
    session.exec("PRAGMA busy_timeout = ${budget.millis()}")
}

internal data class ActiveCallback(val owner: Any, val reader: SqlReadOperations, val writer: SqlOperations?)

private class BudgetedOperations(
    private val delegate: SqlOperations, private val budget: DatabaseWaitBudget,
) : SqlOperations {
    override fun execute(sql: String, args: List<SqlValue>) = budget.measured { delegate.execute(sql, args) }
    override fun <T> query(sql: String, args: List<SqlValue>, map: (SqlRow) -> T): List<T> =
        budget.measured { delegate.query(sql, args, map) }
}
