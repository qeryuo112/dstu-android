package com.deepstudent.services.runtime.backend

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.RuntimeEvent
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskSubmission
import java.time.Instant

data class LeaseToken(val taskId: TaskId, val generation: Long, val ownerEpoch: Long, val ownerId: String)

data class LedgerCreate(val snapshot: RuntimeSnapshot, val inserted: Boolean)

sealed interface LedgerResult {
    data class Applied(val snapshot: RuntimeSnapshot) : LedgerResult
    data class Unchanged(val snapshot: RuntimeSnapshot) : LedgerResult
    data class Rejected(val snapshot: RuntimeSnapshot, val reason: LedgerRejection) : LedgerResult
    data object Missing : LedgerResult
}

enum class LedgerRejection {
    OWNED, STALE_OWNER, EXPIRED, CLOCK_REGRESSED, TERMINAL, INVALID_TRANSITION, CANCEL_REQUESTED, FACT_CONFLICT,
    CONFIRMATION_REQUIRED,
}

sealed interface LedgerCommand {
    /** Recovery may claim a cancelled request or interrupted projection solely to check business facts. */
    data class Claim(
        val ownerId: String,
        val until: Instant,
        val allowCancelled: Boolean = false,
        val expected: com.deepstudent.services.runtime.api.RuntimeOwnership? = null,
    ) : LedgerCommand
    data class Renew(val token: LeaseToken, val until: Instant) : LedgerCommand
    data class Transition(val token: LeaseToken, val state: RuntimeState) : LedgerCommand
    data class Finish(
        val token: LeaseToken,
        val state: RuntimeState,
        val error: DstuError? = null,
        val deferCancelled: Boolean = false,
    ) : LedgerCommand
    data class Checkpoint(val token: LeaseToken, val value: JsonValue?) : LedgerCommand
    data class RequireConfirmation(val token: LeaseToken) : LedgerCommand
    data object RequestCancel : LedgerCommand
    data class RequestCancelGeneration(val generation: Long) : LedgerCommand
    /** Call only after the execution owner is known dead or its lease has expired. */
    data class Fence(val token: LeaseToken, val expiredAt: Instant? = null) : LedgerCommand
    data class Reconcile(val fact: BusinessCompletion, val token: LeaseToken? = null) : LedgerCommand
}

/** Each command and its event are committed atomically in runtime.db. */
interface TaskLedger {
    suspend fun create(taskId: TaskId, submission: TaskSubmission, now: Instant): LedgerCreate
    suspend fun snapshot(taskId: TaskId): RuntimeSnapshot?
    suspend fun events(taskId: TaskId, after: TaskCursor): List<RuntimeEvent>
    suspend fun recoverable(afterTaskId: String?, limit: Int): List<RuntimeSnapshot>
    suspend fun apply(taskId: TaskId, command: LedgerCommand, now: Instant): LedgerResult
}

fun RuntimeSnapshot.leaseToken(): LeaseToken? {
    val owner = ownership.ownerId ?: return null
    return LeaseToken(taskId, ownership.generation, ownership.ownerEpoch, owner)
}
