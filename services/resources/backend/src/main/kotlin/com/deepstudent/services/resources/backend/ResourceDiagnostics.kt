package com.deepstudent.services.resources.backend

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.ClockChecks
import com.deepstudent.core.common.time.MonotonicClock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** Fixed phases only; no caller-controlled paths, names, hashes or payload enter the log. */
enum class ResourcePhase { PREFLIGHT, PREPARE, PUBLISH, DATABASE, READ, CLEANUP }

class ResourceTrace internal constructor(
    private val logger: Logger,
    private val clock: MonotonicClock,
    private val correlation: CorrelationId,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ResourceTrace>

    fun <T> phase(phase: ResourcePhase, action: () -> T): T {
        val start = clock.nowNanos()
        try {
            val result = action()
            emit(phase.name, "completed", start)
            return result
        } catch (failure: CancellationException) {
            emit(phase.name, "cancelled", start)
            throw failure
        } catch (failure: Exception) {
            emit(phase.name, "failed", start, failure)
            throw failure
        }
    }

    fun recoverableCleanup(failure: Exception) {
        try {
            emit(ResourcePhase.CLEANUP.name, "recoverable", clock.nowNanos(), failure)
        } catch (_: Exception) { }
    }

    internal fun emit(phase: String, status: String, start: Long, failure: Exception? = null) {
        try {
            val duration = ClockChecks.monotonicElapsedMillis(start, clock.nowNanos()) ?: 0L
            val diagnostic = safeFailure(failure, phase)
            val fields = JsonValue.Object.Builder().put("owner", "services/resources")
                .put("phase", phase).put("stage", diagnostic?.first ?: phase).put("status", status)
                .put("durationMillis", duration)
            diagnostic?.let { (_, cause) -> fields.put("state", cause) }
            logger.log(
                LogRecord(
                    level = if (status == "failed") LogLevel.ERROR else LogLevel.INFO,
                    event = "resources.operation",
                    correlationId = correlation,
                    fields = fields.build(),
                    error = failure?.let { DstuError.internal(resourceCode(it), "operation failed") },
                ),
            )
        } catch (_: Exception) { }
    }
}

internal class ResourceDiagnostics(private val logger: Logger, private val clock: MonotonicClock) {
    suspend fun <T> record(stage: String, action: suspend () -> T): T {
        val trace = ResourceTrace(logger, clock, CorrelationId.parse(UUID.randomUUID().toString()))
        val start = clock.nowNanos()
        try {
            val result = withContext(trace) { action() }
            trace.emit(stage, "completed", start)
            return result
        } catch (failure: CancellationException) {
            trace.emit(stage, "cancelled", start)
            throw failure
        } catch (failure: Exception) {
            trace.emit(stage, "failed", start, failure)
            throw failure
        }
    }
}

private val FIXED_FAILURES = mapOf(
    "RESOURCE_INSUFFICIENT_SPACE" to ("SPACE" to "INSUFFICIENT_SPACE"),
    "INVALID_ATTACHMENT_SIZE" to ("PREFLIGHT" to "INVALID_SIZE"),
    "ATTACHMENT_CORRUPT" to ("READ" to "CORRUPT"),
)

private fun safeFailure(failure: Exception?, phase: String): Pair<String, String>? {
    if (failure == null) return null
    val error = (failure as? DstuException)?.error
        ?: return phase to if (failure is java.io.IOException) "IOException" else "Other"
    FIXED_FAILURES[error.code]?.let { return it }
    return when (error.code) {
        "RESOURCE_OWNERSHIP_UNKNOWN" -> phase to "OWNERSHIP_UNKNOWN"
        "RESOURCE_STORAGE_FAILED" -> safeStorageDetails(error.details)
        else -> phase to "Other"
    }
}

private fun safeStorageDetails(details: Map<String, String>): Pair<String, String>? {
    val stage = details["stage"] ?: return null
    val cause = details["causeClass"] ?: return null
    val validStages = setOf("ROOT", "RESOLVE", "DIRECTORY", "TEMP_WRITE", "PUBLISH", "CLEANUP", "READ", "SOURCE_READ", "OUTPUT", "SPACE")
    if (stage !in validStages) return null
    if (cause !in setOf(
            "AccessDeniedException",
            "FileAlreadyExistsException",
            "NoSuchFileException",
            "AtomicMoveNotSupportedException",
            "FileSystemException",
            "IOException",
            "SecurityException",
            "Other",
        )
    ) {
        return null
    }
    return stage to cause
}

private fun resourceCode(failure: Exception): String {
    val code = (failure as? DstuException)?.error?.code
    return code?.takeIf { it in attachmentCodes } ?: "OTHER"
}

private val attachmentCodes = setOf("RESOURCE_INSUFFICIENT_SPACE", "INVALID_ATTACHMENT", "INVALID_ATTACHMENT_SIZE", "ATTACHMENT_CORRUPT")
