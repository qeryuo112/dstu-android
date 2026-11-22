package com.deepstudent.services.runtime.backend.application.recovery

import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.leaseToken

/** Uses only S1.08's ledger; the acquired snapshot carries its actual lease token. */
internal class RecoveryClaims(private val session: RecoverySession) {
    private val actions get() = session.actions

    suspend fun claimCandidates(): Boolean {
        var after: String? = null
        while (true) {
            var page = emptyList<RuntimeSnapshot>()
            val read = session.attempt(RecoveryPhase.CLAIM) {
                page = actions.ledger.recoverable(after, session.batchSize)
                check(page.size <= session.batchSize)
                check(page.zipWithNext().all { (left, right) -> left.taskId.value < right.taskId.value })
                check(page.isEmpty() || after == null || page.first().taskId.value > after!!)
                true
            }
            if (!read) return false
            if (page.isEmpty()) return true
            for (snapshot in page) {
                if (claim(snapshot) == null) session.blocked.add(snapshot.taskId)
            }
            after = page.last().taskId.value
        }
    }

    suspend fun claim(candidate: RuntimeSnapshot, forFact: Boolean = false): RuntimeSnapshot? {
        cached(candidate, forFact)?.let { return it }
        if (readOnlyCandidate(candidate, forFact)) {
            session.claims[candidate.taskId] = candidate
            return candidate
        }
        return acquire(candidate)
    }

    private fun cached(candidate: RuntimeSnapshot, forFact: Boolean): RuntimeSnapshot? {
        val cached = session.claims[candidate.taskId] ?: return null
        if (!forFact || cached.ownership.ownerId != null) return cached
        return cached.takeIf { !it.execution.confirmationRequired && it.state != RuntimeState.INTERRUPTED }
    }

    private fun readOnlyCandidate(candidate: RuntimeSnapshot, forFact: Boolean): Boolean {
        if (candidate.ownership.ownerId != null) return false
        if (!forFact && candidate.execution.confirmationRequired) return true
        return candidate.state.terminal && candidate.state != RuntimeState.INTERRUPTED
    }

    private suspend fun acquire(candidate: RuntimeSnapshot): RuntimeSnapshot? {
        val acquired = session.attempt(RecoveryPhase.CLAIM, candidate) {
            val expected = releaseDeadOrExpiredOwner(candidate) ?: return@attempt false
            val now = actions.clock.now()
            val result = actions.ledger.apply(
                candidate.taskId,
                LedgerCommand.Claim(actions.ownerId, now.plus(actions.leaseDuration),
                    allowCancelled = true, expected = expected.ownership),
                now,
            ) as? LedgerResult.Applied ?: return@attempt false
            val claimed = result.snapshot
            session.claims[candidate.taskId] = claimed
            session.dirty.add(candidate.taskId)
            claimed.ownership.generation == expected.ownership.generation &&
                claimed.ownership.ownerEpoch == expected.ownership.ownerEpoch + 1
        }
        return if (acquired) session.claims[candidate.taskId] else null
    }

    private suspend fun releaseDeadOrExpiredOwner(candidate: RuntimeSnapshot): RuntimeSnapshot? {
        val token = candidate.leaseToken() ?: return candidate
        val expiredAt = if (actions.deadOwner.isDead(candidate)) {
            null
        } else {
            candidate.ownership.leaseUntil?.takeIf { it <= actions.clock.now() } ?: return null
        }
        val result = apply(candidate, LedgerCommand.Fence(token, expiredAt)) as? LedgerResult.Applied ?: return null
        session.dirty.add(candidate.taskId)
        return result.snapshot
    }

    suspend fun reconcile(snapshot: RuntimeSnapshot, fact: BusinessCompletion): Boolean {
        val token = snapshot.leaseToken()
        if ((!snapshot.state.terminal || snapshot.state == com.deepstudent.services.runtime.api.RuntimeState.INTERRUPTED) &&
            token == null
        ) return false
        return session.attempt(RecoveryPhase.RECONCILE, snapshot) {
            val result = apply(snapshot, LedgerCommand.Reconcile(fact, token))
            when (result) {
                is LedgerResult.Applied -> {
                    session.dirty.add(snapshot.taskId)
                    true
                }
                is LedgerResult.Unchanged -> true
                else -> false
            }
        }
    }

    suspend fun releaseClaims() {
        for ((taskId, snapshot) in session.claims) {
            if (taskId in session.transferred) continue
            if (taskId !in session.settled) release(snapshot)
        }
    }

    private suspend fun release(snapshot: RuntimeSnapshot) {
        val token = snapshot.leaseToken() ?: return
        session.attempt(RecoveryPhase.RELEASE, snapshot) {
            val result = apply(snapshot, LedgerCommand.Fence(token))
            if (result is LedgerResult.Applied) session.dirty.add(snapshot.taskId)
            result !is LedgerResult.Unchanged
        }
    }

    private suspend fun apply(snapshot: RuntimeSnapshot, command: LedgerCommand): LedgerResult =
        actions.ledger.apply(snapshot.taskId, command, actions.clock.now())
}
