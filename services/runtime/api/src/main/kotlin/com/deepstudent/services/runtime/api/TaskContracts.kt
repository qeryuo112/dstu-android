package com.deepstudent.services.runtime.api

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.number.Int64Bounds
import java.time.Instant

enum class TaskStatus(val wire: String) {
    PENDING("Pending"),
    PROCESSING("Processing"),
    STREAMING("Streaming"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    TRUNCATED("Truncated"),
    CANCELLED("Cancelled"),
    ;

    val terminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == TRUNCATED || this == CANCELLED

    companion object {
        fun fromWire(value: String): TaskStatus =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown task status: $value")
    }
}

@JvmInline
value class TaskKind private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TaskKind {
            require(raw.isNotBlank() && raw.length <= 128) { "invalid task kind" }
            return TaskKind(raw)
        }
    }
}

@JvmInline
value class TaskCursor private constructor(val value: Long) {
    companion object {
        val ZERO: TaskCursor = TaskCursor(0)

        fun parse(raw: Long): TaskCursor {
            require(raw >= 0) { "task cursor must be non-negative" }
            Int64Bounds.requireJsonSafe(raw, "task cursor")
            return TaskCursor(raw)
        }
    }
}

data class TaskSubmission(
    val kind: TaskKind,
    val payload: JsonValue = JsonValue.Object.EMPTY,
    val correlationId: CorrelationId? = null,
    val dedupeKey: String? = null,
) {
    init {
        require(dedupeKey == null || dedupeKey.isNotBlank()) { "dedupeKey must not be blank" }
    }
}

data class TaskHandle(
    val taskId: TaskId,
    val deduplicated: Boolean = false,
)

data class TaskTimes(
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TaskProgress(
    val percent: Int? = null,
    val error: DstuError? = null,
) {
    init {
        require(percent == null || percent in 0..100) {
            "task progress must be within 0..100"
        }
    }
}

data class TaskSnapshot(
    val taskId: TaskId,
    val kind: TaskKind,
    val status: TaskStatus,
    val correlationId: CorrelationId?,
    val times: TaskTimes,
    val progress: TaskProgress = TaskProgress(),
    val result: JsonValue? = null,
) {
    val createdAt: Instant get() = times.createdAt
    val updatedAt: Instant get() = times.updatedAt
    val progressPercent: Int? get() = progress.percent
    val error: DstuError? get() = progress.error
}

sealed interface TaskEventPayload {
    data class StateChanged(val snapshot: TaskSnapshot) : TaskEventPayload

    data class ProgressChanged(val taskId: TaskId, val percent: Int) : TaskEventPayload {
        init {
            require(percent in 0..100) { "task progress must be within 0..100" }
        }
    }

    data class Completed(val taskId: TaskId, val result: JsonValue?) : TaskEventPayload

    data class Failed(val taskId: TaskId, val error: DstuError) : TaskEventPayload

    data class Cancelled(val taskId: TaskId, val reason: String?) : TaskEventPayload
}

data class TaskEvent(
    val sequence: TaskCursor,
    val payload: TaskEventPayload,
)

sealed interface TaskCancelResult {
    data class Requested(val taskId: TaskId) : TaskCancelResult

    data class AlreadyTerminal(val taskId: TaskId, val status: TaskStatus) : TaskCancelResult

    data class NotFound(val taskId: TaskId) : TaskCancelResult

    data class Rejected(val taskId: TaskId, val error: DstuError) : TaskCancelResult
}
