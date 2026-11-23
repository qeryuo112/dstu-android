package com.deepstudent.services.runtime.backend

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.services.runtime.api.RuntimeOwnership
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import java.time.Duration

class LeaseManager(
    private val ledger: TaskLedger,
    private val clock: WallClock,
    private val ownerId: String,
    private val duration: Duration,
) {
    init {
        require(ownerId.isNotBlank() && duration >= Duration.ofMillis(3)) { "invalid lease configuration" }
        require(!RecoveryLease.isRecoveryOnly(ownerId)) { "reserved recovery owner" }
    }

    val renewalIntervalMillis: Long = duration.toMillis() / 3

    /** Normal execution claim. The stored owner id is the plain [ownerId]. */
    suspend fun claim(taskId: TaskId): LeaseToken? {
        val now = clock.now()
        val result = ledger.apply(taskId, LedgerCommand.Claim(ownerId, now.plus(duration)), now)
        return (result as? LedgerResult.Applied)?.snapshot?.leaseToken()
    }

    suspend fun renew(token: LeaseToken): RuntimeSnapshot {
        val now = clock.now()
        return ledger.apply(token.taskId, LedgerCommand.Renew(token, now.plus(duration)), now).requireApplied()
    }

    /** Explicit fenced release. Returns false when this token no longer owns the task. */
    suspend fun release(token: LeaseToken): Boolean {
        val now = clock.now()
        return when (ledger.apply(token.taskId, LedgerCommand.Release(token), now)) {
            is LedgerResult.Applied, is LedgerResult.Unchanged -> true
            is LedgerResult.Rejected, LedgerResult.Missing -> false
        }
    }

    /**
     * Takes over a lease that has already expired, but only if the stored ownership still equals
     * [expected] (generation, ownerEpoch, ownerId and leaseUntil). The result is a recovery-only
     * token persisted under the reserved owner namespace: it can verify business facts and settle
     * recovery, and the supervisor refuses to execute with it.
     */
    suspend fun takeOverExpired(taskId: TaskId, expected: RuntimeOwnership): LeaseToken? {
        val now = clock.now()
        val result = ledger.apply(
            taskId,
            LedgerCommand.TakeOver(expected, RecoveryLease.ownerId(ownerId), now.plus(duration)),
            now,
        )
        return (result as? LedgerResult.Applied)?.snapshot?.leaseToken()
    }
}

class LeaseLostException : IllegalStateException("task lease lost")

internal fun LedgerResult.requireApplied(): RuntimeSnapshot = when (this) {
    is LedgerResult.Applied -> snapshot
    is LedgerResult.Unchanged -> snapshot
    is LedgerResult.Rejected, LedgerResult.Missing -> throw LeaseLostException()
}
