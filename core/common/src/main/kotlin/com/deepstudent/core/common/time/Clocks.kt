package com.deepstudent.core.common.time

import java.time.Instant
import java.util.concurrent.TimeUnit

fun interface WallClock {
    fun now(): Instant
}

fun interface MonotonicClock {
    fun nowNanos(): Long
}

object SystemWallClock : WallClock {
    override fun now(): Instant = Instant.now()
}

object SystemMonotonicClock : MonotonicClock {
    override fun nowNanos(): Long = System.nanoTime()
}

object ClockChecks {
    fun wallRegressed(previous: Instant, current: Instant): Boolean = current.isBefore(previous)

    /**
     * Returns null when either instant cannot be represented as epoch millis or
     * when the millisecond subtraction overflows int64. A negative value is a
     * real wall-clock rollback and is returned as-is.
     */
    fun wallDeltaMillis(previous: Instant, current: Instant): Long? =
        try {
            Math.subtractExact(current.toEpochMilli(), previous.toEpochMilli())
        } catch (failure: ArithmeticException) {
            null
        }

    /**
     * Monotonic readings use two's-complement wrapping. A non-negative wrapped
     * delta is accepted (including a single signed rollover). A negative delta
     * means either a real rollback or a forward gap >= 2^63 ns; both fail
     * closed as null. No negative elapsed duration is ever returned.
     */
    fun monotonicElapsedNanos(start: Long, end: Long): Long? {
        val delta = end - start
        return delta.takeIf { it >= 0 }
    }

    fun monotonicElapsedMillis(start: Long, end: Long): Long? =
        monotonicElapsedNanos(start, end)?.let { TimeUnit.NANOSECONDS.toMillis(it) }
}

fun interface RandomSource {
    fun nextBytes(count: Int): ByteArray
}
