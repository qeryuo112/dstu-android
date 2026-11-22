package com.deepstudent.services.runtime.api

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import java.time.Instant

/** Local execution lifecycle. This is not the frozen wire [TaskStatus]. */
enum class RuntimeState {
    CREATED, RUNNING, WAITING_APPROVAL, PAUSED, COMPLETED, FAILED, CANCELLED, INTERRUPTED;

    val terminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == INTERRUPTED
}

data class RuntimeOwnership(
    val generation: Long,
    val ownerEpoch: Long,
    val ownerId: String?,
    val leaseUntil: Instant?,
)

data class RuntimeExecution(
    val checkpoint: JsonValue?,
    val cancelRequested: Boolean,
    val receipt: String?,
    val businessFactId: String?,
    val error: DstuError?,
    val confirmationRequired: Boolean = false,
)

data class RuntimeSnapshot(
    val taskId: TaskId,
    val kind: TaskKind,
    val state: RuntimeState,
    val submission: TaskSubmission,
    val ownership: RuntimeOwnership,
    val execution: RuntimeExecution,
    val times: TaskTimes,
    val cursor: TaskCursor,
) {
    val correlationId: CorrelationId? get() = submission.correlationId
}

enum class RuntimeEventType {
    CREATED, STATE_CHANGED, CANCEL_REQUESTED, CHECKPOINT, LEASE_CHANGED, RECONCILED, CONFIRMATION_REQUIRED,
}

/** Cursor is globally ordered in runtime.db; query and observation still filter by task. */
data class RuntimeEvent(
    val cursor: TaskCursor,
    val taskId: TaskId,
    val generation: Long,
    val type: RuntimeEventType,
    val state: RuntimeState,
    val at: Instant,
)

sealed interface RuntimeCancelResult {
    data class Requested(val taskId: TaskId) : RuntimeCancelResult
    data class AlreadyTerminal(val taskId: TaskId, val state: RuntimeState) : RuntimeCancelResult
    data class NotFound(val taskId: TaskId) : RuntimeCancelResult
    data class StaleGeneration(val taskId: TaskId) : RuntimeCancelResult
}
