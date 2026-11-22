package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.DatabaseBusyException
import com.deepstudent.platform.database.DatabaseLog
import com.deepstudent.platform.database.DatabaseLogEvent
import com.deepstudent.platform.database.DatabasePhase
import com.deepstudent.platform.database.DatabaseProfile
import com.deepstudent.platform.database.DatabaseStatus
import com.deepstudent.platform.database.DatabaseWait
import com.deepstudent.platform.database.MigrationDiagnostic
import com.deepstudent.platform.database.MigrationException
import com.deepstudent.platform.database.MigrationReport
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.platform.database.SqlValue
import java.util.concurrent.TimeUnit

internal class DatabaseLease(
    private val engine: DatabaseEngine,
    private val profile: DatabaseProfile,
    private val log: DatabaseLog,
    override val migrationReport: MigrationReport?,
    private val release: () -> Unit,
) : SqlDatabase {
    private val monitor = Object()
    private var closing = false
    private var active = 0
    private var releasing = false
    private var released = false
    private var releaseFailure: Throwable? = null

    override fun execute(sql: String, args: List<SqlValue>) {
        val current = engine.active(this)
        if (current != null) {
            val writer = current.writer ?: throw MigrationException(MigrationDiagnostic.DATABASE_FAILED)
            writer.execute(sql, args)
        } else transaction { execute(sql, args) }
    }
    override fun <T> query(sql: String, args: List<SqlValue>, map: (SqlRow) -> T): List<T> {
        val current = engine.active(this)
        return if (current != null) current.reader.query(sql, args, map) else readSnapshot { query(sql, args, map) }
    }

    override fun <T> transaction(block: SqlOperations.() -> T): T = operation(DatabasePhase.WRITE) { engine.write(this, block) }
    override fun <T> readSnapshot(block: SqlReadOperations.() -> T): T = operation(DatabasePhase.READ) { engine.read(this, block) }

    private fun <T> operation(phase: DatabasePhase, block: () -> T): T {
        engine.checkNotReentrant()
        synchronized(monitor) {
            if (closing) throw MigrationException(MigrationDiagnostic.DATABASE_FAILED)
            active++
        }
        try { return logged(log, phase, block) } finally { finishOperation() }
    }

    private fun finishOperation() {
        val finalize = synchronized(monitor) {
            active--
            beginReleaseIfReady()
        }
        if (finalize) finalizeRelease()
    }

    override fun close() {
        engine.checkNotReentrant()
        logged(log, DatabasePhase.CLOSE) {
            val finalize = synchronized(monitor) { closing = true; beginReleaseIfReady() }
            if (finalize) finalizeRelease()
            awaitReleased()
        }
    }

    private fun beginReleaseIfReady(): Boolean {
        if (!closing || active != 0 || releasing) return false
        releasing = true
        return true
    }

    private fun finalizeRelease() {
        var failure: Throwable? = null
        try { release() } catch (thrown: Throwable) { failure = thrown }
        synchronized(monitor) { releaseFailure = failure; released = true; monitor.notifyAll() }
    }

    private fun awaitReleased() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(profile.lockWaitMillis)
        synchronized(monitor) {
            while (!released) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw DatabaseBusyException(DatabaseWait.CLOSE)
                try { monitor.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt()) }
                catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.util.concurrent.CancellationException("database wait interrupted")
        }
            }
            releaseFailure?.let { throw it }
        }
    }
}

internal fun <T> logged(log: DatabaseLog, phase: DatabasePhase, block: () -> T): T {
    val start = System.nanoTime()
    var status = DatabaseStatus.SUCCESS
    var wait: DatabaseWait? = null
    try { return block() } catch (failure: Throwable) {
        status = if (failure is DatabaseBusyException) DatabaseStatus.TIMED_OUT else DatabaseStatus.FAILED
        wait = (failure as? DatabaseBusyException)?.stage
        throw failure
    } finally {
        val elapsed = (System.nanoTime() - start).coerceAtLeast(0) / 1_000_000
        runCatching { log.onEvent(DatabaseLogEvent(phase, status, elapsed, wait)) }
    }
}
