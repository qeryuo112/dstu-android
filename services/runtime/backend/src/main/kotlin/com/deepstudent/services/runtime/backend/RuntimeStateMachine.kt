package com.deepstudent.services.runtime.backend

import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.RuntimeEventType
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import java.time.Instant

data class LedgerChange(val result: LedgerResult, val event: RuntimeEventType? = null)

/** Pure rule engine; persistence must serialize the read, decision and write. */
object RuntimeStateMachine {
    fun decide(snapshot: RuntimeSnapshot, command: LedgerCommand, now: Instant): LedgerChange = when (command) {
        is LedgerCommand.Claim -> claim(snapshot, command, now)
        is LedgerCommand.Renew -> renew(snapshot, command, now)
        is LedgerCommand.Fence -> fence(snapshot, command, now)
        is LedgerCommand.Release -> releaseLease(snapshot, command, now)
        is LedgerCommand.TakeOver -> takeOver(snapshot, command, now)
        else -> executionDecision(snapshot, command, now)
    }

    private fun executionDecision(snapshot: RuntimeSnapshot, command: LedgerCommand, now: Instant): LedgerChange = when (command) {
        is LedgerCommand.Transition -> transition(snapshot, command, now)

        is LedgerCommand.Finish -> finish(snapshot, command, now)

        is LedgerCommand.Checkpoint -> checkpoint(snapshot, command, now)

        is LedgerCommand.RequireConfirmation -> requireConfirmation(snapshot, command, now)

        LedgerCommand.RequestCancel -> cancel(snapshot, now)

        is LedgerCommand.RequestCancelGeneration -> {
            cancelGeneration(snapshot, command.generation, now)
        }

        is LedgerCommand.Reconcile -> reconcile(snapshot, command, now)

        else -> rejected(snapshot, LedgerRejection.INVALID_TRANSITION)
    }

    private fun cancelGeneration(snapshot: RuntimeSnapshot, generation: Long, now: Instant): LedgerChange =
        if (snapshot.ownership.generation == generation) {
            cancel(snapshot, now)
        } else {
            rejected(snapshot, LedgerRejection.STALE_OWNER)
        }

    private fun claim(s: RuntimeSnapshot, c: LedgerCommand.Claim, now: Instant): LedgerChange {
        claimRejection(s, c)?.let { return rejected(s, it) }
        if (s.ownership.ownerId != null) return rejected(s, LedgerRejection.OWNED)
        if (now < s.times.updatedAt) return rejected(s, LedgerRejection.CLOCK_REGRESSED)
        require(c.ownerId.isNotBlank() && c.until > now) { "invalid lease" }
        if (RecoveryLease.isRecoveryOnly(c.ownerId)) return rejected(s, LedgerRejection.INVALID_TRANSITION)
        val owner = s.ownership.copy(
            ownerId = c.ownerId,
            ownerEpoch = Math.addExact(s.ownership.ownerEpoch, 1),
            leaseUntil = c.until,
        )
        return changed(s.copy(ownership = owner), RuntimeEventType.LEASE_CHANGED, now)
    }

    private fun claimRejection(s: RuntimeSnapshot, c: LedgerCommand.Claim): LedgerRejection? {
        if (c.expected != null && c.expected != s.ownership) return LedgerRejection.STALE_OWNER
        if (s.state.terminal) {
            if (!c.allowCancelled || s.state != RuntimeState.INTERRUPTED) return LedgerRejection.TERMINAL
        }
        return if (c.allowCancelled) null else claimExecutionRejection(s)
    }

    private fun claimExecutionRejection(s: RuntimeSnapshot): LedgerRejection? = when {
        s.execution.cancelRequested -> LedgerRejection.CANCEL_REQUESTED
        s.execution.confirmationRequired -> LedgerRejection.CONFIRMATION_REQUIRED
        else -> null
    }

    private fun renew(s: RuntimeSnapshot, c: LedgerCommand.Renew, now: Instant): LedgerChange {
        invalidOwner(s, c.token, now)?.let { return rejected(s, it) }
        require(c.until > now) { "invalid lease" }
        if (c.until < requireNotNull(s.ownership.leaseUntil)) return rejected(s, LedgerRejection.CLOCK_REGRESSED)
        return changed(s.copy(ownership = s.ownership.copy(leaseUntil = c.until)), RuntimeEventType.LEASE_CHANGED, now)
    }

    private fun transition(s: RuntimeSnapshot, c: LedgerCommand.Transition, now: Instant): LedgerChange {
        invalidOwner(s, c.token, now)?.let { return rejected(s, it) }
        if (c.token.recoveryOnly) return rejected(s, LedgerRejection.RECOVERY_ONLY)
        if (s.execution.cancelRequested) return rejected(s, LedgerRejection.CANCEL_REQUESTED)
        if (s.execution.confirmationRequired) return rejected(s, LedgerRejection.CONFIRMATION_REQUIRED)
        if (s.state == c.state) return LedgerChange(LedgerResult.Unchanged(s))
        if (!canTransition(s.state, c.state)) return rejected(s, LedgerRejection.INVALID_TRANSITION)
        return changed(s.copy(state = c.state), RuntimeEventType.STATE_CHANGED, now)
    }

    private fun finish(s: RuntimeSnapshot, c: LedgerCommand.Finish, now: Instant): LedgerChange {
        finishRejection(s, c, now)?.let { return rejected(s, it) }
        val state = if (s.execution.cancelRequested) RuntimeState.CANCELLED else c.state
        val error = if (state == RuntimeState.FAILED) {
            com.deepstudent.core.common.error.DstuError.internal(
                "TASK_FAILED",
                "task execution failed",
            )
        } else {
            null
        }
        val execution = s.execution.copy(error = error)
        return changed(s.copy(state = state, execution = execution, ownership = release(s)), RuntimeEventType.STATE_CHANGED, now)
    }

    private fun finishRejection(s: RuntimeSnapshot, c: LedgerCommand.Finish, now: Instant): LedgerRejection? {
        invalidOwner(s, c.token, now)?.let { return it }
        if (c.token.recoveryOnly && c.state != RuntimeState.INTERRUPTED) return LedgerRejection.RECOVERY_ONLY
        if (s.execution.confirmationRequired) return LedgerRejection.CONFIRMATION_REQUIRED
        if (!validFinish(c)) return LedgerRejection.INVALID_TRANSITION
        return if (s.execution.cancelRequested && c.deferCancelled) LedgerRejection.CANCEL_REQUESTED else null
    }

    private fun validFinish(command: LedgerCommand.Finish): Boolean =
        command.state.terminal && (command.error == null || command.state == RuntimeState.FAILED)

    private fun checkpoint(s: RuntimeSnapshot, c: LedgerCommand.Checkpoint, now: Instant): LedgerChange {
        invalidOwner(s, c.token, now)?.let { return rejected(s, it) }
        if (c.token.recoveryOnly) return rejected(s, LedgerRejection.RECOVERY_ONLY)
        if (s.execution.cancelRequested) return rejected(s, LedgerRejection.CANCEL_REQUESTED)
        if (s.execution.confirmationRequired) return rejected(s, LedgerRejection.CONFIRMATION_REQUIRED)
        return changed(s.copy(execution = s.execution.copy(checkpoint = c.value)), RuntimeEventType.CHECKPOINT, now)
    }

    /**
     * Recovery-only claims may record a confirmation decision: it fences the owner and pauses the
     * task without executing anything, which is a recovery settlement rather than a rerun.
     */
    private fun requireConfirmation(s: RuntimeSnapshot, c: LedgerCommand.RequireConfirmation, now: Instant): LedgerChange {
        invalidOwner(s, c.token, now)?.let { return rejected(s, it) }
        val owner = release(s).copy(ownerEpoch = Math.addExact(s.ownership.ownerEpoch, 1))
        val execution = s.execution.copy(confirmationRequired = true)
        return changed(
            s.copy(state = RuntimeState.PAUSED, ownership = owner, execution = execution),
            RuntimeEventType.CONFIRMATION_REQUIRED,
            now,
        )
    }

    private fun cancel(s: RuntimeSnapshot, now: Instant): LedgerChange {
        if (s.state.terminal) return rejected(s, LedgerRejection.TERMINAL)
        if (s.execution.cancelRequested) return LedgerChange(LedgerResult.Unchanged(s))
        val execution = s.execution.copy(cancelRequested = true)
        val state = if (s.ownership.ownerId == null && s.ownership.ownerEpoch == 0L) RuntimeState.CANCELLED else s.state
        return changed(s.copy(state = state, execution = execution), RuntimeEventType.CANCEL_REQUESTED, now)
    }

    private fun fence(s: RuntimeSnapshot, c: LedgerCommand.Fence, now: Instant): LedgerChange {
        if (s.state.terminal && s.state != RuntimeState.INTERRUPTED) return rejected(s, LedgerRejection.TERMINAL)
        if (!matches(s, c.token)) return rejected(s, LedgerRejection.STALE_OWNER)
        if (c.expiredAt != null && !expiredLeaseMatches(s, c.expiredAt, now)) return rejected(s, LedgerRejection.OWNED)
        val ownership = release(s).copy(ownerEpoch = Math.addExact(s.ownership.ownerEpoch, 1))
        return changed(s.copy(ownership = ownership), RuntimeEventType.LEASE_CHANGED, now)
    }

    private fun expiredLeaseMatches(s: RuntimeSnapshot, expectedExpiry: Instant, now: Instant): Boolean =
        s.ownership.leaseUntil == expectedExpiry && expectedExpiry <= now

    /**
     * Explicit fenced release. Only a token that still matches the stored ownership may release,
     * including an expired holder; the owner_epoch then advances so a delayed write with the old
     * token is STALE_OWNER. Repeating the same release after it committed returns Unchanged, but
     * only for the same task, generation and one-step epoch advance. Task state is untouched.
     */
    private fun releaseLease(s: RuntimeSnapshot, c: LedgerCommand.Release, now: Instant): LedgerChange {
        terminalRejection(s)?.let { return rejected(s, it) }
        if (now < s.times.updatedAt) return rejected(s, LedgerRejection.CLOCK_REGRESSED)
        if (!matches(s, c.token)) {
            return if (alreadyReleased(s, c.token)) {
                LedgerChange(LedgerResult.Unchanged(s))
            } else {
                rejected(s, LedgerRejection.STALE_OWNER)
            }
        }
        val ownership = release(s).copy(ownerEpoch = Math.addExact(s.ownership.ownerEpoch, 1))
        return changed(s.copy(ownership = ownership), RuntimeEventType.LEASE_CHANGED, now)
    }

    private fun alreadyReleased(s: RuntimeSnapshot, token: LeaseToken): Boolean =
        s.taskId == token.taskId && s.ownership.generation == token.generation &&
            s.ownership.ownerId == null && s.ownership.ownerEpoch > 0 &&
            s.ownership.ownerEpoch - 1 == token.ownerEpoch

    /**
     * Atomic takeover after expiry. Requires exact expected generation/epoch/owner/expiry and
     * `expected.leaseUntil <= now`; a renewed live lease or a replaced owner is rejected. The new
     * owner id must use the reserved recovery namespace. Cancellation, confirmation, state and
     * checkpoint are preserved so the claim can only verify facts and settle recovery.
     */
    private fun takeOver(s: RuntimeSnapshot, c: LedgerCommand.TakeOver, now: Instant): LedgerChange {
        takeOverRejection(s, c, now)?.let { return rejected(s, it) }
        val ownership = s.ownership.copy(
            ownerId = c.ownerId,
            ownerEpoch = Math.addExact(s.ownership.ownerEpoch, 1),
            leaseUntil = c.until,
        )
        return changed(s.copy(ownership = ownership), RuntimeEventType.LEASE_CHANGED, now)
    }

    private fun takeOverRejection(s: RuntimeSnapshot, c: LedgerCommand.TakeOver, now: Instant): LedgerRejection? {
        terminalRejection(s)?.let { return it }
        if (s.ownership != c.expected) return LedgerRejection.STALE_OWNER
        expiredOwnershipRejection(s, now)?.let { return it }
        return takeOverLeaseRejection(c, now)
    }

    private fun expiredOwnershipRejection(s: RuntimeSnapshot, now: Instant): LedgerRejection? {
        if (s.ownership.ownerId == null) return LedgerRejection.STALE_OWNER
        val expiry = s.ownership.leaseUntil ?: return LedgerRejection.STALE_OWNER
        if (expiry > now) return LedgerRejection.OWNED
        return if (now < s.times.updatedAt) LedgerRejection.CLOCK_REGRESSED else null
    }

    private fun takeOverLeaseRejection(c: LedgerCommand.TakeOver, now: Instant): LedgerRejection? {
        if (!RecoveryLease.isRecoveryOnly(c.ownerId)) return LedgerRejection.INVALID_TRANSITION
        return if (c.until <= now) LedgerRejection.INVALID_TRANSITION else null
    }

    private fun terminalRejection(s: RuntimeSnapshot): LedgerRejection? =
        if (s.state.terminal && s.state != RuntimeState.INTERRUPTED) LedgerRejection.TERMINAL else null

    private fun reconcile(s: RuntimeSnapshot, c: LedgerCommand.Reconcile, now: Instant): LedgerChange {
        val fact = c.fact
        if (fact.taskId != s.taskId || fact.generation != s.ownership.generation) {
            return rejected(s, LedgerRejection.FACT_CONFLICT)
        }
        existingFact(s, fact)?.let { return it }
        reconciliationOwner(s, c, now)?.let { return rejected(s, it) }
        if (conflictsWithTerminal(s, fact)) return rejected(s, LedgerRejection.FACT_CONFLICT)
        val execution = s.execution.copy(receipt = fact.receipt, businessFactId = fact.factId, error = null, confirmationRequired = false)
        return changed(s.copy(state = fact.state, execution = execution, ownership = release(s)), RuntimeEventType.RECONCILED, now)
    }

    private fun reconciliationOwner(s: RuntimeSnapshot, command: LedgerCommand.Reconcile, now: Instant): LedgerRejection? {
        if (command.token != null && !matches(s, command.token)) return LedgerRejection.STALE_OWNER
        if (s.ownership.ownerId == null) return null
        val token = command.token ?: return LedgerRejection.STALE_OWNER
        return invalidOwner(s, token, now, allowInterrupted = true)
    }

    private fun conflictsWithTerminal(s: RuntimeSnapshot, fact: BusinessCompletion): Boolean =
        s.state.terminal && s.state != RuntimeState.INTERRUPTED && s.state != fact.state

    private fun existingFact(s: RuntimeSnapshot, fact: BusinessCompletion): LedgerChange? {
        val prior = s.execution.businessFactId ?: return null
        return if (prior == fact.factId && s.execution.receipt == fact.receipt && s.state == fact.state) {
            LedgerChange(LedgerResult.Unchanged(s))
        } else {
            rejected(s, LedgerRejection.FACT_CONFLICT)
        }
    }

    private fun invalidOwner(s: RuntimeSnapshot, token: LeaseToken, now: Instant, allowInterrupted: Boolean = false): LedgerRejection? {
        val until = s.ownership.leaseUntil
        return when {
            s.state.terminal && !(allowInterrupted && s.state == RuntimeState.INTERRUPTED) -> LedgerRejection.TERMINAL
            !matches(s, token) -> LedgerRejection.STALE_OWNER
            now < s.times.updatedAt -> LedgerRejection.CLOCK_REGRESSED
            until == null || until <= now -> LedgerRejection.EXPIRED
            else -> null
        }
    }

    private fun matches(s: RuntimeSnapshot, t: LeaseToken): Boolean = s.taskId == t.taskId && s.ownership.generation == t.generation &&
        s.ownership.ownerEpoch == t.ownerEpoch && s.ownership.ownerId == t.ownerId

    private fun canTransition(from: RuntimeState, to: RuntimeState): Boolean = when (from) {
        RuntimeState.CREATED -> to == RuntimeState.RUNNING
        RuntimeState.RUNNING -> to == RuntimeState.WAITING_APPROVAL || to == RuntimeState.PAUSED
        RuntimeState.WAITING_APPROVAL, RuntimeState.PAUSED -> to == RuntimeState.RUNNING
        else -> false
    }

    private fun release(s: RuntimeSnapshot) = s.ownership.copy(ownerId = null, leaseUntil = null)

    private fun rejected(s: RuntimeSnapshot, reason: LedgerRejection) = LedgerChange(LedgerResult.Rejected(s, reason))

    private fun changed(s: RuntimeSnapshot, event: RuntimeEventType, now: Instant) =
        LedgerChange(LedgerResult.Applied(s.copy(times = s.times.copy(updatedAt = now))), event)
}
