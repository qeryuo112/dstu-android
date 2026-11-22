package com.deepstudent.services.runtime.backend

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.RuntimeExecution
import com.deepstudent.services.runtime.api.RuntimeOwnership
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.api.TaskTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RuntimeStateMachineTest {
    private val now = Instant.parse("2026-11-16T00:00:00Z")
    private val taskId = TaskId.parse("task-1")
    private val kind = TaskKind.parse("notes.save")
    private val created = RuntimeSnapshot(
        taskId, kind, RuntimeState.CREATED, TaskSubmission(kind), RuntimeOwnership(1, 0, null, null),
        RuntimeExecution(null, false, null, null, null), TaskTimes(now, now), TaskCursor.ZERO,
    )

    @Test
    fun ownerEpochFencesOldOwnerAndLeaseExpiry() {
        val first = apply(created, LedgerCommand.Claim("owner-1", now.plusSeconds(10)))
        val token = requireNotNull(first.leaseToken())
        val running = apply(first, LedgerCommand.Transition(token, RuntimeState.RUNNING))
        val fenced = apply(running, LedgerCommand.Fence(token))
        val second = apply(fenced, LedgerCommand.Claim("owner-2", now.plusSeconds(10)))
        assertEquals(LedgerRejection.STALE_OWNER, rejection(second, LedgerCommand.Checkpoint(token, JsonValue.text("old"))))
        val expired = RuntimeStateMachine.decide(second, LedgerCommand.Renew(requireNotNull(second.leaseToken()), now.plusSeconds(20)), now.plusSeconds(11))
        assertEquals(LedgerRejection.EXPIRED, (expired.result as LedgerResult.Rejected).reason)
        val regressed = RuntimeStateMachine.decide(second, LedgerCommand.Checkpoint(requireNotNull(second.leaseToken()), null), now.minusSeconds(1))
        assertEquals(LedgerRejection.CLOCK_REGRESSED, (regressed.result as LedgerResult.Rejected).reason)
    }

    @Test
    fun cancellationWinsAtomicFinishAndTerminalCannotFinishAgain() {
        val claimed = apply(created, LedgerCommand.Claim("owner", now.plusSeconds(10)))
        val token = requireNotNull(claimed.leaseToken())
        val running = apply(claimed, LedgerCommand.Transition(token, RuntimeState.RUNNING))
        val requested = apply(running, LedgerCommand.RequestCancel)
        val finished = apply(requested, LedgerCommand.Finish(token, RuntimeState.COMPLETED))
        assertEquals(RuntimeState.CANCELLED, finished.state)
        assertEquals(LedgerRejection.TERMINAL, rejection(finished, LedgerCommand.Finish(token, RuntimeState.FAILED)))
    }

    @Test
    fun onlyValidNonterminalTransitionsAreAccepted() {
        val claimed = apply(created, LedgerCommand.Claim("owner", now.plusSeconds(10)))
        val token = requireNotNull(claimed.leaseToken())
        assertEquals(LedgerRejection.INVALID_TRANSITION, rejection(claimed, LedgerCommand.Transition(token, RuntimeState.PAUSED)))
        val running = apply(claimed, LedgerCommand.Transition(token, RuntimeState.RUNNING))
        val approval = apply(running, LedgerCommand.Transition(token, RuntimeState.WAITING_APPROVAL))
        assertEquals(RuntimeState.RUNNING, apply(approval, LedgerCommand.Transition(token, RuntimeState.RUNNING)).state)
    }

    @Test
    fun businessFactIsAuthoritativeIdempotentAndCannotBeReplaced() {
        val fact = BusinessCompletion("fact-1", taskId, 1, RuntimeState.COMPLETED, "receipt-1", now)
        val reconciled = apply(created, LedgerCommand.Reconcile(fact))
        assertEquals(RuntimeState.COMPLETED, reconciled.state)
        assertEquals("receipt-1", reconciled.execution.receipt)
        assertTrue(RuntimeStateMachine.decide(reconciled, LedgerCommand.Reconcile(fact), now).result is LedgerResult.Unchanged)
        assertEquals(LedgerRejection.FACT_CONFLICT, rejection(reconciled, LedgerCommand.Reconcile(fact.copy(factId = "other"))))
    }

    @Test
    fun staleOwnerCannotReconcileAndCancelledRecoveryCanClaim() {
        val first = apply(created, LedgerCommand.Claim("old", now.plusSeconds(10)))
        val old = requireNotNull(first.leaseToken())
        val running = apply(first, LedgerCommand.Transition(old, RuntimeState.RUNNING))
        val requested = apply(running, LedgerCommand.RequestCancel)
        val fenced = apply(requested, LedgerCommand.Fence(old))
        assertEquals(RuntimeState.RUNNING, fenced.state)
        val recovery = apply(fenced, LedgerCommand.Claim("recovery", now.plusSeconds(10), allowCancelled = true))
        val fact = BusinessCompletion("fact-1", taskId, 1, RuntimeState.COMPLETED, "receipt-1", now)
        assertEquals(LedgerRejection.STALE_OWNER, rejection(recovery, LedgerCommand.Reconcile(fact, old)))
        val resolved = apply(recovery, LedgerCommand.Reconcile(fact, requireNotNull(recovery.leaseToken())))
        assertEquals(RuntimeState.COMPLETED, resolved.state)
    }

    @Test
    fun interruptedProjectionMayBeCorrectedByDurableBusinessFact() {
        val claimed = apply(created, LedgerCommand.Claim("owner", now.plusSeconds(10)))
        val token = requireNotNull(claimed.leaseToken())
        val interrupted = apply(claimed, LedgerCommand.Finish(token, RuntimeState.INTERRUPTED))
        val fact = BusinessCompletion("fact-1", taskId, 1, RuntimeState.COMPLETED, "receipt-1", now)
        val recovery = apply(interrupted, LedgerCommand.Claim("recovery", now.plusSeconds(10), allowCancelled = true))
        assertEquals(RuntimeState.COMPLETED, apply(recovery, LedgerCommand.Reconcile(fact, recovery.leaseToken())).state)
    }

    @Test
    fun confirmationFencesOwnerAndBlocksAutomaticResume() {
        val claimed = apply(created, LedgerCommand.Claim("owner", now.plusSeconds(10)))
        val token = requireNotNull(claimed.leaseToken())
        val running = apply(claimed, LedgerCommand.Transition(token, RuntimeState.RUNNING))
        val checkpoint = apply(running, LedgerCommand.Checkpoint(token, JsonValue.text("partial")))
        val paused = apply(checkpoint, LedgerCommand.RequireConfirmation(token))
        assertEquals(RuntimeState.PAUSED, paused.state)
        assertTrue(paused.execution.confirmationRequired)
        assertEquals(JsonValue.text("partial"), paused.execution.checkpoint)
        assertEquals(LedgerRejection.CONFIRMATION_REQUIRED, rejection(paused, LedgerCommand.Claim("worker", now.plusSeconds(10))))
        assertEquals(LedgerRejection.STALE_OWNER, rejection(paused, LedgerCommand.Finish(token, RuntimeState.COMPLETED)))
        val recovery = apply(paused, LedgerCommand.Claim("recovery", now.plusSeconds(10), allowCancelled = true))
        val newToken = requireNotNull(recovery.leaseToken())
        assertEquals(LedgerRejection.CONFIRMATION_REQUIRED, rejection(recovery, LedgerCommand.Transition(newToken, RuntimeState.RUNNING)))
        assertEquals(LedgerRejection.CONFIRMATION_REQUIRED, rejection(recovery, LedgerCommand.Checkpoint(newToken, null)))
        assertEquals(LedgerRejection.CONFIRMATION_REQUIRED, rejection(recovery, LedgerCommand.Finish(newToken, RuntimeState.INTERRUPTED)))
        val fact = BusinessCompletion("fact", taskId, 1, RuntimeState.COMPLETED, "receipt", now)
        assertFalse(apply(recovery, LedgerCommand.Reconcile(fact, newToken)).execution.confirmationRequired)
    }

    private fun apply(snapshot: RuntimeSnapshot, command: LedgerCommand): RuntimeSnapshot =
        (RuntimeStateMachine.decide(snapshot, command, now).result as LedgerResult.Applied).snapshot

    @Test fun claimAndCancelCompareExpectedOwnershipInsideTheDecision() {
        val claimed = apply(created, LedgerCommand.Claim("owner", now.plusSeconds(10)))
        val token = requireNotNull(claimed.leaseToken())
        val released = apply(claimed, LedgerCommand.Fence(token))
        assertEquals(LedgerRejection.STALE_OWNER, rejection(released,
            LedgerCommand.Claim("recovery", now.plusSeconds(10), expected = created.ownership)))
        assertEquals(LedgerRejection.STALE_OWNER, rejection(released, LedgerCommand.RequestCancelGeneration(2)))
        val fact = BusinessCompletion("fenced-fact", taskId, 1, RuntimeState.COMPLETED, "receipt", now)
        assertEquals(LedgerRejection.STALE_OWNER, rejection(released, LedgerCommand.Reconcile(fact, token)))
    }

    @Test fun expiredFenceCannotStealRenewedOrLiveLease() {
        val claimed = apply(created, LedgerCommand.Claim("owner", now.plusSeconds(10)))
        val token = requireNotNull(claimed.leaseToken())
        assertEquals(LedgerRejection.OWNED, rejection(claimed, LedgerCommand.Fence(token, now.plusSeconds(10))))
        val result = RuntimeStateMachine.decide(claimed,
            LedgerCommand.Fence(token, now.plusSeconds(10)), now.plusSeconds(11))
        assertTrue(result.result is LedgerResult.Applied)
    }

    private fun rejection(snapshot: RuntimeSnapshot, command: LedgerCommand): LedgerRejection =
        (RuntimeStateMachine.decide(snapshot, command, now).result as LedgerResult.Rejected).reason
}
