package com.deepstudent.services.runtime.backend

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.time.WallClock
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
    }

    val renewalIntervalMillis: Long = duration.toMillis() / 3

    suspend fun claim(taskId: TaskId): LeaseToken? {
        val now = clock.now()
        val result = ledger.apply(taskId, LedgerCommand.Claim(ownerId, now.plus(duration)), now)
        return (result as? LedgerResult.Applied)?.snapshot?.leaseToken()
    }

    suspend fun renew(token: LeaseToken): RuntimeSnapshot {
        val now = clock.now()
        return ledger.apply(token.taskId, LedgerCommand.Renew(token, now.plus(duration)), now).requireApplied()
    }
}

class LeaseLostException : IllegalStateException("task lease lost")

internal fun LedgerResult.requireApplied(): RuntimeSnapshot = when (this) {
    is LedgerResult.Applied -> snapshot
    is LedgerResult.Unchanged -> snapshot
    is LedgerResult.Rejected, LedgerResult.Missing -> throw LeaseLostException()
}
