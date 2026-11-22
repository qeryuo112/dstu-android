package com.deepstudent.platform.storage

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import java.util.UUID

internal class DataSpaceLogger(private val logger: Logger, private val audit: DataSpaceAudit) {
    fun <T> operation(phase: DataSpacePhase, block: (Trace) -> T): T {
        val correlationId = UUID.randomUUID().toString()
        val start = System.nanoTime()
        emit(correlationId, phase, DataSpaceOutcome.STARTED, start, null)
        try {
            val result = block(Trace(correlationId, start))
            emit(correlationId, phase, DataSpaceOutcome.SUCCEEDED, start, null)
            return result
        } catch (error: DataSpaceException) {
            emit(correlationId, phase, DataSpaceOutcome.REJECTED, start, error.error)
            throw error
        } catch (_: Exception) {
            emit(correlationId, phase, DataSpaceOutcome.FAILED, start, DataSpaceError.IO_FAILURE)
            throw DataSpaceException(DataSpaceError.IO_FAILURE)
        }
    }

    inner class Trace(private val id: String, private val start: Long) {
        fun checkpoint(phase: DataSpacePhase) = emit(id, phase, DataSpaceOutcome.SUCCEEDED, start, null)
    }

    private fun emit(id: String, phase: DataSpacePhase, outcome: DataSpaceOutcome, start: Long, error: DataSpaceError?) {
        val event = DataSpaceEvent(id, phase, outcome, (System.nanoTime() - start) / 1_000_000, error)
        try {
            audit.record(event)
        } catch (_: Exception) { /* Logging cannot alter storage decisions. */ }
        val fields = JsonValue.Object.Builder().put("owner", event.owner)
            .put("phase", phase.name).put("state", outcome.name).put("elapsedMs", event.elapsedMillis)
        if (error != null) fields.put("errorCode", error.name)
        try {
            logger.log(LogRecord(LogLevel.INFO, "storage.dataspace.operation", correlationId = CorrelationId.parse(id),
                fields = fields.build()))
        } catch (_: Exception) { /* Logging cannot alter storage decisions. */ }
    }
}
