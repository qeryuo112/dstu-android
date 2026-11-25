package com.deepstudent.features.notes.backend.application

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.ClockChecks
import com.deepstudent.core.common.time.MonotonicClock
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

internal class NoteDiagnostics(private val logger: Logger, private val clock: MonotonicClock) {
    suspend fun <T> record(stage: String, action: suspend () -> T): T {
        val correlation = CorrelationId.parse(UUID.randomUUID().toString())
        val start = clock.nowNanos()
        try {
            val result = action()
            emit(stage, "completed", correlation, start)
            return result
        } catch (failure: CancellationException) {
            emit(stage, "cancelled", correlation, start)
            throw failure
        } catch (failure: Exception) {
            emit(stage, "failed", correlation, start, DstuError.internal(noteCode(failure), "operation failed"))
            throw failure
        }
    }

    private fun emit(stage: String, status: String, correlation: CorrelationId, start: Long, error: DstuError? = null) {
        val duration = ClockChecks.monotonicElapsedMillis(start, clock.nowNanos()) ?: 0L
        val fields = JsonValue.Object.Builder().put("owner", "features/notes").put("stage", stage)
            .put("status", status).put("durationMillis", duration).build()
        val record = LogRecord(
            level = if (status == "failed") LogLevel.ERROR else LogLevel.INFO,
            event = "notes.operation",
            correlationId = correlation,
            fields = fields,
            error = error,
        )
        try {
            logger.log(record)
        } catch (_: Exception) { }
    }
}

private fun noteCode(failure: Exception): String {
    val code = (failure as? DstuException)?.error?.code
    return code?.takeIf { it in noteCodes } ?: "OTHER"
}

private val noteCodes = setOf("INVALID_NOTE_TITLE", "INVALID_NOTE_TAGS", "INVALID_NOTE_ID")
