package com.deepstudent.platform.background

import android.os.SystemClock
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One wake-lock owner. It mirrors a foreground responsibility but never invents one: a task owner
 * carries the runtime generation, a continuous owner carries the process-local session id.
 *
 * The budget window is the owner identity itself. A task id contains its generation and a
 * continuous id belongs to exactly one session, so a new generation or a new session starts a new
 * budget while the same owner can never renew itself past its cap. That is what keeps renewals
 * finite.
 */
data class WakeOwner(val kind: WakeOwnerKind, val id: String) {
    init {
        require(id.matches(OWNER_PATTERN)) { "invalid wake owner" }
    }

    private companion object {
        val OWNER_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}")
    }
}

enum class WakeOwnerKind {
    TASK,
    CONTINUOUS,
}

fun wakeOwner(duty: ForegroundDuty): WakeOwner = WakeOwner(WakeOwnerKind.TASK, "${duty.taskId.value}.g${duty.generation}")

fun wakeOwner(duty: ContinuousDuty): WakeOwner = WakeOwner(WakeOwnerKind.CONTINUOUS, duty.ownerId)

/** A stage inside one owner. At most one segment per owner and phase is ever held. */
enum class WakePhase {
    /** Active work while the device may be dozing. */
    WORK,

    /** Persisting results or checkpoints after work finished. */
    FINALIZE,

    /** An interactive continuous session, e.g. a user terminal. */
    SESSION,
}

/**
 * Per-segment and per-owner caps. A segment reserves `min(segmentMillis, remaining budget)`; the
 * system timeout is that reservation, so Android always drops the lock even if this process stalls.
 */
data class WakeLockLimits(val segmentMillis: Long = 10 * 60_000L, val budgetMillis: Long = 4 * 60 * 60_000L) {
    init {
        require(segmentMillis > 0 && segmentMillis <= budgetMillis) { "invalid wake lock limits" }
    }
}

/** Platform lock handle. [acquire] must use the system timeout, never an unbounded acquire. */
interface WakeLockHandle {
    val isHeld: Boolean

    fun acquire(timeoutMillis: Long): Boolean

    fun release()
}

fun interface WakeLockFactory {
    fun create(owner: WakeOwner, phase: WakePhase): WakeLockHandle
}

enum class WakeReleaseReason {
    /** The caller finished its segment. */
    SEGMENT_END,

    /** The duty behind the owner ended; the platform releases eagerly. */
    DUTY_LOST,

    /** The foreground service or process scope is being destroyed. */
    SERVICE_DESTROYED,

    /** The guarded work failed; the lock is still released. */
    FAILURE,
}

enum class WakeDenial {
    BUDGET_EXHAUSTED,
    PLATFORM_REJECTED,
    CLOSED,
}

sealed interface WakeAcquireResult {
    /** A new segment was created and the platform acquired the lock. */
    data class Held(val lease: WakeLease) : WakeAcquireResult

    /** The same owner and phase already hold a live segment; its deadline is not extended. */
    data class Reused(val lease: WakeLease) : WakeAcquireResult

    data class Denied(val reason: WakeDenial) : WakeAcquireResult
}

sealed interface WakeSegmentResult<out T> {
    data class Ran<T>(val value: T) : WakeSegmentResult<T>

    data class Denied(val reason: WakeDenial) : WakeSegmentResult<Nothing>
}

/** One bounded hold. [release] is idempotent; the first call settles and logs the segment. */
class WakeLease internal constructor(
    val owner: WakeOwner,
    val phase: WakePhase,
    val deadlineMillis: Long,
    private val onRelease: (WakeReleaseReason) -> Unit,
) : Closeable {
    internal val released = AtomicBoolean(false)

    fun release(reason: WakeReleaseReason = WakeReleaseReason.SEGMENT_END) {
        if (released.compareAndSet(false, true)) onRelease(reason)
    }

    override fun close() = release(WakeReleaseReason.SEGMENT_END)
}

/**
 * Segmented wake-lock controller.
 *
 * Invariants:
 * - one live segment per owner and phase; a second acquire is [WakeAcquireResult.Reused] and never
 *   pushes the deadline out;
 * - every segment has a system timeout and a remaining-budget bound, so a stalled process cannot
 *   hold a lock forever;
 * - budget is charged by real elapsed time only, so an early release returns the unused reservation
 *   without resetting the owner's budget;
 * - release, expiry, denial and close all write one structured record with a fixed field set and no
 *   owner or business text.
 */
class WakeLockController(
    private val factory: WakeLockFactory,
    private val logger: Logger,
    private val limits: (WakePhase) -> WakeLockLimits = { WakeLockLimits() },
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) : Closeable {
    private val lock = Any()
    private val segments = mutableMapOf<WakeKey, Segment>()
    private val consumed = mutableMapOf<WakeKey, Long>()
    private var closed = false

    fun acquire(owner: WakeOwner, phase: WakePhase): WakeAcquireResult = synchronized(lock) {
        if (closed) return@synchronized denied(WakeDenial.CLOSED, phase)
        val key = WakeKey(owner, phase)
        val now = nowMillis()
        val existing = segments[key]
        if (existing != null) {
            if (existing.live(now)) {
                val spent = consumed[key] ?: 0L
                log(
                    WakeCategory.REUSE,
                    phase,
                    "already_held",
                    now - existing.startedMillis,
                    existing.reservedMillis,
                    (limits(phase).budgetMillis - spent).coerceAtLeast(0L),
                )
                return@synchronized WakeAcquireResult.Reused(existing.lease)
            }
            // The system timeout already dropped the lock or this process stalled past the deadline.
            settleExpired(key, existing, now)
        }
        val policy = limits(phase)
        val spent = consumed[key] ?: 0L
        val remaining = (policy.budgetMillis - spent).coerceAtLeast(0L)
        if (remaining <= 0L) return@synchronized denied(WakeDenial.BUDGET_EXHAUSTED, phase)
        val reserved = minOf(policy.segmentMillis, remaining)
        val handle = factory.create(owner, phase)
        val acquired = runCatching { handle.acquire(reserved) }.getOrDefault(false)
        if (!acquired) {
            runCatching { handle.release() }
            return@synchronized denied(WakeDenial.PLATFORM_REJECTED, phase)
        }
        val segment = Segment(
            owner = owner,
            phase = phase,
            handle = handle,
            startedMillis = now,
            deadlineMillis = now + reserved,
            reservedMillis = reserved,
            lease = WakeLease(owner, phase, now + reserved) { reason -> synchronized(lock) { settle(key, reason) } },
        )
        segments[key] = segment
        log(WakeCategory.ACQUIRE, phase, "requested", 0L, reserved, remaining - reserved)
        WakeAcquireResult.Held(segment.lease)
    }

    /**
     * Runs [block] inside one bounded segment. A denied segment never runs the block. A reused
     * segment (the same owner and phase is already held elsewhere) runs the block but leaves the
     * existing release in charge of the lock, so this call cannot shorten someone else's hold.
     */
    suspend fun <T> withSegment(owner: WakeOwner, phase: WakePhase, block: suspend () -> T): WakeSegmentResult<T> {
        val acquired = acquire(owner, phase)
        if (acquired is WakeAcquireResult.Denied) return WakeSegmentResult.Denied(acquired.reason)
        val lease = when (acquired) {
            is WakeAcquireResult.Held -> acquired.lease
            is WakeAcquireResult.Reused -> acquired.lease
            is WakeAcquireResult.Denied -> error("unreachable")
        }
        val owning = acquired is WakeAcquireResult.Held
        try {
            return WakeSegmentResult.Ran(block())
        } catch (failure: Throwable) {
            if (owning) lease.release(WakeReleaseReason.FAILURE)
            throw failure
        } finally {
            if (owning) lease.release(WakeReleaseReason.SEGMENT_END)
        }
    }

    /** Ends every phase of one owner, e.g. when its duty is retired. */
    fun release(owner: WakeOwner) {
        val keys = synchronized(lock) { segments.keys.filter { it.owner == owner } }
        keys.forEach { key -> synchronized(lock) { settle(key, WakeReleaseReason.DUTY_LOST) } }
    }

    /** Ends every hold, e.g. from [RuntimeForegroundService.onDestroy]. */
    fun releaseAll(reason: WakeReleaseReason = WakeReleaseReason.SERVICE_DESTROYED) {
        val keys = synchronized(lock) { segments.keys.toList() }
        keys.forEach { key -> synchronized(lock) { settle(key, reason) } }
    }

    fun heldPhases(owner: WakeOwner): Set<WakePhase> = synchronized(lock) {
        reconcileExpired()
        segments.keys.filter { it.owner == owner }.map { it.phase }.toSet()
    }

    fun activeOwners(): Set<WakeOwner> = synchronized(lock) {
        reconcileExpired()
        segments.keys.map { it.owner }.toSet()
    }

    override fun close() {
        synchronized(lock) { closed = true }
        releaseAll(WakeReleaseReason.SERVICE_DESTROYED)
        log(WakeCategory.CLOSE, null, "service_destroyed", 0L, 0L, 0L)
    }

    private fun reconcileExpired() {
        val now = nowMillis()
        segments.entries.filter { !it.value.live(now) }.forEach { (key, segment) -> settleExpired(key, segment, now) }
    }

    private fun settleExpired(key: WakeKey, segment: Segment, now: Long) {
        val reason = if (now >= segment.deadlineMillis) "timeout" else "revoked"
        settle(key, WakeReleaseReason.SEGMENT_END, WakeCategory.EXPIRE, reason, now)
    }

    private fun settle(
        key: WakeKey,
        reason: WakeReleaseReason,
        category: WakeCategory = WakeCategory.RELEASE,
        token: String = reason.token(),
        now: Long = nowMillis(),
    ) {
        val segment = segments.remove(key) ?: return
        // Marks the lease closed so a later release() from the caller is a no-op.
        segment.lease.released.compareAndSet(false, true)
        runCatching { segment.handle.release() }
        val elapsed = (now - segment.startedMillis).coerceIn(0L, segment.reservedMillis)
        val spent = (consumed[key] ?: 0L) + elapsed
        consumed[key] = spent
        val remaining = (limits(key.phase).budgetMillis - spent).coerceAtLeast(0L)
        log(category, key.phase, token, elapsed, segment.reservedMillis, remaining)
    }

    private fun denied(reason: WakeDenial, phase: WakePhase): WakeAcquireResult.Denied {
        log(WakeCategory.DENY, phase, reason.token(), 0L, 0L, 0L)
        return WakeAcquireResult.Denied(reason)
    }

    private fun log(
        category: WakeCategory,
        phase: WakePhase?,
        reason: String,
        elapsedMillis: Long,
        capMillis: Long,
        remainingMillis: Long,
    ) {
        val fields = JsonValue.Object.Builder()
            .put("owner", MODULE_OWNER)
            .put("category", category.token())
            .put("phase", phase?.token() ?: "none")
            .put("reason", reason)
            .put("elapsedMs", elapsedMillis)
            .put("capMs", capMillis)
            .put("remainingMs", remainingMillis)
        try {
            logger.log(LogRecord(LogLevel.INFO, EVENT, fields = fields.build()))
        } catch (_: Exception) {
            // Diagnostics never block lock release.
        }
    }

    private data class WakeKey(val owner: WakeOwner, val phase: WakePhase)

    private class Segment(
        val owner: WakeOwner,
        val phase: WakePhase,
        val handle: WakeLockHandle,
        val startedMillis: Long,
        val deadlineMillis: Long,
        val reservedMillis: Long,
        val lease: WakeLease,
    ) {
        fun live(now: Long): Boolean = now < deadlineMillis && runCatching { handle.isHeld }.getOrDefault(false)
    }

    private companion object {
        const val EVENT = "background.wakelock"
        const val MODULE_OWNER = "platform.background"
    }
}

private enum class WakeCategory {
    ACQUIRE,
    REUSE,
    RELEASE,
    EXPIRE,
    DENY,
    CLOSE,
    ;

    fun token(): String = name.lowercase()
}

private fun WakeReleaseReason.token(): String = name.lowercase()

private fun WakeDenial.token(): String = name.lowercase()

private fun WakePhase.token(): String = name.lowercase()
