package com.deepstudent.services.runtime.backend.application.recovery

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.ClockChecks
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.services.runtime.api.RuntimeSnapshot

internal enum class RecoveryPhase { CLAIM, OUTBOX, LOOKUP, RECONCILE, DECIDE, RELEASE, PUBLISH }
internal enum class RecoveryOutcome { APPLIED, SKIPPED, BLOCKED, FAILED }

/** Fixed vocabulary only: never log checkpoints, receipts, handler errors or exception text. */
internal class RecoveryLog(private val logger: Logger, private val clock: MonotonicClock) {
    fun start(): Long = clock.nowNanos()

    fun record(
        phase: RecoveryPhase,
        outcome: RecoveryOutcome,
        started: Long,
        snapshot: RuntimeSnapshot? = null,
    ) {
        val fields = JsonValue.Object.Builder()
            .put("phase", phase.name)
            .put("outcome", outcome.name)
            .put("owner", "runtime.recovery")
        ClockChecks.monotonicElapsedMillis(started, clock.nowNanos())?.let { fields.put("duration_ms", it) }
        val level = if (outcome == RecoveryOutcome.FAILED) LogLevel.ERROR else LogLevel.INFO
        try {
            logger.log(LogRecord(level, "runtime.recovery", snapshot?.taskId, snapshot?.correlationId, fields.build()))
        } catch (_: Exception) {
            // Diagnostics must not change the outcome of a committed recovery step.
        }
    }
}
