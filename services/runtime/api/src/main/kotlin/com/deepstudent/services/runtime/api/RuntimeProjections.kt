package com.deepstudent.services.runtime.api

import com.deepstudent.core.common.id.TaskId
import java.time.Instant

/** A generation-scoped address; a task ID alone is not enough to update its projections. */
data class RuntimeTaskGeneration(val taskId: TaskId, val generation: Long) {
    init {
        require(generation > 0) { "invalid task generation" }
    }
}

/** Opaque identifier or machine code, not a capability, a user-facing reason or a payload. */
@JvmInline
value class RuntimeRecordKey private constructor(val value: String) {
    companion object {
        private val pattern = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")

        fun parse(value: String): RuntimeRecordKey {
            require(pattern.matches(value)) { "invalid runtime record key" }
            return RuntimeRecordKey(value)
        }
    }
}

enum class RuntimeApprovalState { PENDING, APPROVED, DENIED, CANCELLED }

data class RuntimeApprovalRecord(
    val id: RuntimeRecordKey,
    val operation: RuntimeRecordKey,
    val state: RuntimeApprovalState,
    val requestedAt: Instant,
    val updatedAt: Instant,
    val cursor: TaskCursor,
)

enum class RuntimeAuditAction { APPROVAL_REQUESTED, APPROVAL_RESOLVED, DUTY_REGISTERED, DUTY_RELEASED, DECISION_RECORDED }

data class RuntimeAuditRecord(
    val id: RuntimeRecordKey,
    val action: RuntimeAuditAction,
    val code: RuntimeRecordKey,
    val at: Instant,
    val cursor: TaskCursor,
)

data class RuntimeDutyRecord(
    val id: RuntimeRecordKey,
    val purpose: RuntimeRecordKey,
    val registeredAt: Instant,
    val releasedAt: Instant?,
    val cursor: TaskCursor,
)

enum class RuntimeProjectionResult { APPLIED, UNCHANGED, CONFLICT, MISSING, STALE_GENERATION, CLOSED }

sealed interface RuntimeProjectionChange {
    val id: RuntimeRecordKey

    data class RequestApproval(override val id: RuntimeRecordKey, val operation: RuntimeRecordKey) : RuntimeProjectionChange
    data class ResolveApproval(override val id: RuntimeRecordKey, val state: RuntimeApprovalState) : RuntimeProjectionChange {
        init {
            require(state != RuntimeApprovalState.PENDING) { "approval decision is required" }
        }
    }
    data class RecordDecision(override val id: RuntimeRecordKey, val code: RuntimeRecordKey) : RuntimeProjectionChange
    data class RegisterDuty(override val id: RuntimeRecordKey, val purpose: RuntimeRecordKey) : RuntimeProjectionChange
    data class ReleaseDuty(override val id: RuntimeRecordKey) : RuntimeProjectionChange
}

/** Local task records. This port neither grants capabilities nor starts Android foreground services. */
interface RuntimeProjectionStore {
    suspend fun apply(task: RuntimeTaskGeneration, change: RuntimeProjectionChange, now: Instant): RuntimeProjectionResult
    suspend fun approvals(task: RuntimeTaskGeneration, after: RuntimeRecordKey?, limit: Int): List<RuntimeApprovalRecord>
    suspend fun audit(task: RuntimeTaskGeneration, after: TaskCursor, limit: Int): List<RuntimeAuditRecord>
    suspend fun duties(task: RuntimeTaskGeneration, after: RuntimeRecordKey?, limit: Int): List<RuntimeDutyRecord>
}
