package com.deepstudent.services.runtime.backend

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.RuntimeEvent
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskSubmission
import java.time.Instant

/**
 * Reserved owner namespace for expired-lease takeover. The marker is persisted in
 * `runtime_tasks.owner_id`, so a reopen derives the same recovery-only capability
 * from the stored row. Normal claims may not use this namespace.
 */
object RecoveryLease {
    const val OWNER_PREFIX = "@recovery-lease/"

    fun ownerId(owner: String): String {
        require(owner.isNotBlank() && !owner.startsWith(OWNER_PREFIX)) { "invalid recovery owner" }
        return OWNER_PREFIX + owner
    }

    fun isRecoveryOnly(ownerId: String?): Boolean = ownerId?.startsWith(OWNER_PREFIX) == true

    fun logicalOwner(ownerId: String): String = ownerId.removePrefix(OWNER_PREFIX)
}

data class LeaseToken(val taskId: TaskId, val generation: Long, val ownerEpoch: Long, val ownerId: String) {
    /** Derived from the persisted owner id; never an in-memory-only flag. */
    val recoveryOnly: Boolean get() = RecoveryLease.isRecoveryOnly(ownerId)
}

data class LedgerCreate(val snapshot: RuntimeSnapshot, val inserted: Boolean)

sealed interface LedgerResult {
    data class Applied(val snapshot: RuntimeSnapshot) : LedgerResult
    data class Unchanged(val snapshot: RuntimeSnapshot) : LedgerResult
    data class Rejected(val snapshot: RuntimeSnapshot, val reason: LedgerRejection) : LedgerResult
    data object Missing : LedgerResult
}

enum class LedgerRejection {
    OWNED,
    STALE_OWNER,
    EXPIRED,
    CLOCK_REGRESSED,
    TERMINAL,
    INVALID_TRANSITION,
    CANCEL_REQUESTED,
    FACT_CONFLICT,
    CONFIRMATION_REQUIRED,

    /** A recovery-only lease may inspect facts and settle recovery, never run the task again. */
    RECOVERY_ONLY,
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
    data class Finish(val token: LeaseToken, val state: RuntimeState, val error: DstuError? = null, val deferCancelled: Boolean = false) :
        LedgerCommand
    data class Checkpoint(val token: LeaseToken, val value: JsonValue?) : LedgerCommand
    data class RequireConfirmation(val token: LeaseToken) : LedgerCommand
    data object RequestCancel : LedgerCommand
    data class RequestCancelGeneration(val generation: Long) : LedgerCommand

    /** Call only after the execution owner is known dead or its lease has expired. */
    data class Fence(val token: LeaseToken, val expiredAt: Instant? = null) : LedgerCommand

    /**
     * Explicit fenced release by the current owner: clears ownership and advances owner_epoch.
     * Later writes carrying the old token are rejected as STALE_OWNER.
     */
    data class Release(val token: LeaseToken) : LedgerCommand

    /**
     * Atomic takeover of an expired lease. Succeeds only when the stored ownership still equals
     * [expected] and `expected.leaseUntil` is already at or before `now`. [ownerId] must use the
     * reserved [RecoveryLease] namespace; the resulting claim is recovery-only (derived from the
     * persisted owner id), so it may read business facts and settle recovery but never execute
     * the task again. Cancellation and confirmation flags are preserved; task state is unchanged.
     */
    data class TakeOver(val expected: com.deepstudent.services.runtime.api.RuntimeOwnership, val ownerId: String, val until: Instant) :
        LedgerCommand

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
