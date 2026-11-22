package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.DatabaseBusyException
import com.deepstudent.platform.database.DatabaseErrorKind
import com.deepstudent.platform.database.DatabaseWait
import java.util.concurrent.TimeUnit

/** Counts time spent acquiring/using SQLite locks, not time in caller-owned callbacks. */
internal class DatabaseWaitBudget(millis: Long) {
    private var remaining = TimeUnit.MILLISECONDS.toNanos(millis)
    fun nanos(): Long = remaining.coerceAtLeast(0)
    fun millis(): Long = ((nanos() + 999_999L) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong())

    fun <T> measured(block: () -> T): T {
        val start = System.nanoTime()
        try { return block() } finally { remaining = (remaining - (System.nanoTime() - start).coerceAtLeast(0)).coerceAtLeast(0) }
    }
}

internal fun <T> mapBusy(block: () -> T): T = try {
    block()
} catch (failure: DatabaseFailure) {
    if (failure.error.kind == DatabaseErrorKind.BUSY) throw DatabaseBusyException(DatabaseWait.SQLITE)
    throw failure
}

internal fun interruptible(block: () -> Boolean, stage: DatabaseWait) {
    val acquired = try { block() } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw java.util.concurrent.CancellationException("database wait interrupted")
    }
    if (!acquired) throw DatabaseBusyException(stage)
}
