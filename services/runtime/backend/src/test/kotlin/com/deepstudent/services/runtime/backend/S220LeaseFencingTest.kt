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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** S2.20: explicit fenced release and expected-token/expiry takeover, without a running database. */
class S220LeaseFencingTest {
    private val start = Instant.parse("2026-11-23T00:00:00Z")
    private val taskId = TaskId.parse("s220-task")
    private val kind = TaskKind.parse("s220.test")
    private var at = start
    private val created = RuntimeSnapshot(
        taskId,
        kind,
        RuntimeState.CREATED,
        TaskSubmission(kind),
        RuntimeOwnership(1, 0, null, null),
        RuntimeExecution(null, false, null, null, null),
        TaskTimes(start, start),
        TaskCursor.ZERO,
    )

    @Test
    fun releaseRequiresLiveTokenAdvancesEpochAndFencesDelayedWrites() {
        val claimed = apply(created, LedgerCommand.Claim("ui", start.plusSeconds(10)))
        val token = requireNotNull(claimed.leaseToken())
        val running = apply(claimed, LedgerCommand.Transition(token, RuntimeState.RUNNING))

        val released = apply(running, LedgerCommand.Release(token))
        assertNull(released.ownership.ownerId)
        assertNull(released.ownership.leaseUntil)
        assertEquals(token.ownerEpoch + 1, released.ownership.ownerEpoch)
        assertEquals(RuntimeState.RUNNING, released.state)

        assertEquals(LedgerRejection.STALE_OWNER, rejection(released, LedgerCommand.Checkpoint(token, JsonValue.text("late"))))
        assertEquals(LedgerRejection.STALE_OWNER, rejection(released, LedgerCommand.Renew(token, at.plusSeconds(20))))
        assertTrue(apply(released, LedgerCommand.Release(token)) === released)

        val worker = apply(released, LedgerCommand.Claim("worker", at.plusSeconds(10)))
        assertTrue(requireNotNull(worker.leaseToken()).ownerEpoch > released.ownership.ownerEpoch)
        assertEquals(LedgerRejection.STALE_OWNER, rejection(worker, LedgerCommand.Checkpoint(token, JsonValue.text("late"))))
    }

    @Test
    fun releaseIdempotencyAndClockGuardStayScopedToTheSameTaskAndGeneration() {
        val claimed = apply(created, LedgerCommand.Claim("ui", start.plusSeconds(10)))
        val token = requireNotNull(claimed.leaseToken())
        at = start.plusSeconds(5)
        val released = apply(claimed, LedgerCommand.Release(token))
        assertEquals(start.plusSeconds(5), released.times.updatedAt)

        val otherTask = TaskId.parse("s220-other")
        val foreign = created.copy(taskId = otherTask, ownership = released.ownership)
        assertEquals(LedgerRejection.STALE_OWNER, rejection(foreign, LedgerCommand.Release(token)))
        assertEquals(
            LedgerRejection.STALE_OWNER,
            rejection(
                released,
                LedgerCommand.Release(token.copy(generation = token.generation + 1)),
            ),
        )
        assertEquals(
            LedgerRejection.STALE_OWNER,
            rejection(
                released,
                LedgerCommand.Release(token.copy(ownerEpoch = token.ownerEpoch - 1)),
            ),
        )
        assertTrue(RuntimeStateMachine.decide(released, LedgerCommand.Release(token), at).result is LedgerResult.Unchanged)

        at = start.plusSeconds(2)
        assertEquals(LedgerRejection.CLOCK_REGRESSED, rejection(released, LedgerCommand.Release(token)))

        at = start.plusSeconds(10)
        val liveClaim = apply(created, LedgerCommand.Claim("later", at.plusSeconds(10)))
        at = start.plusSeconds(1)
        assertEquals(
            LedgerRejection.CLOCK_REGRESSED,
            rejection(liveClaim, LedgerCommand.Release(requireNotNull(liveClaim.leaseToken()))),
        )
    }

    @Test
    fun expiredHolderMayStillReleaseButOnlyWithItsExactToken() {
        val claimed = apply(created, LedgerCommand.Claim("ui", start.plusSeconds(10)))
        val token = requireNotNull(claimed.leaseToken())
        at = start.plusSeconds(11)
        val released = apply(claimed, LedgerCommand.Release(token))
        assertNull(released.ownership.ownerId)
        assertEquals(token.ownerEpoch + 1, released.ownership.ownerEpoch)
        assertEquals(LedgerRejection.STALE_OWNER, rejection(released, LedgerCommand.Release(token.copy(generation = 2))))
        assertEquals(LedgerRejection.STALE_OWNER, rejection(released, LedgerCommand.Release(token.copy(ownerEpoch = token.ownerEpoch + 5))))
    }

    @Test
    fun releaseOnATerminalTaskIsRejectedAndTheOldOwnerCannotWrite() {
        val claimed = apply(created, LedgerCommand.Claim("ui", start.plusSeconds(10)))
        val token = requireNotNull(claimed.leaseToken())
        val running = apply(claimed, LedgerCommand.Transition(token, RuntimeState.RUNNING))
        val finished = apply(running, LedgerCommand.Finish(token, RuntimeState.COMPLETED))
        assertEquals(RuntimeState.COMPLETED, finished.state)
        assertEquals(LedgerRejection.TERMINAL, rejection(finished, LedgerCommand.Release(token)))
        assertEquals(LedgerRejection.TERMINAL, rejection(finished, LedgerCommand.Checkpoint(token, null)))
    }

    @Test
    fun takeOverRequiresTheExactExpiredOwnershipAndRejectsLiveOrRenewedLeases() {
        val claimed = apply(created, LedgerCommand.Claim("ui", start.plusSeconds(10)))
        val expired = claimed.ownership

        at = start.plusSeconds(9)
        assertEquals(
            LedgerRejection.OWNED,
            rejection(
                claimed,
                LedgerCommand.TakeOver(expired, RecoveryLease.ownerId("recovery"), at.plusSeconds(30)),
            ),
        )

        at = start.plusSeconds(10)
        val taken = apply(claimed, LedgerCommand.TakeOver(expired, RecoveryLease.ownerId("recovery"), at.plusSeconds(30)))
        assertEquals(RecoveryLease.ownerId("recovery"), taken.ownership.ownerId)
        assertEquals(expired.ownerEpoch + 1, taken.ownership.ownerEpoch)
        assertEquals(RuntimeState.CREATED, taken.state)
        val recoveryToken = requireNotNull(taken.leaseToken())
        assertTrue(recoveryToken.recoveryOnly)

        assertEquals(
            LedgerRejection.STALE_OWNER,
            rejection(
                taken,
                LedgerCommand.TakeOver(expired, RecoveryLease.ownerId("other"), at.plusSeconds(30)),
            ),
        )

        val renewed = apply(created, LedgerCommand.Claim("ui", start.plusSeconds(30)))
        val renewedToken = requireNotNull(renewed.leaseToken())
        val live = apply(renewed, LedgerCommand.Renew(renewedToken, start.plusSeconds(60)))
        assertEquals(
            LedgerRejection.STALE_OWNER,
            rejection(
                live,
                LedgerCommand.TakeOver(renewed.ownership, RecoveryLease.ownerId("recovery"), at.plusSeconds(30)),
            ),
        )
        assertEquals(
            LedgerRejection.OWNED,
            rejection(
                live,
                LedgerCommand.TakeOver(live.ownership, RecoveryLease.ownerId("recovery"), at.plusSeconds(30)),
            ),
        )
    }

    @Test
    fun reservedNamespaceIsNeverAcceptedAsANormalClaimOrTakeover() {
        assertEquals(
            LedgerRejection.INVALID_TRANSITION,
            rejection(
                created,
                LedgerCommand.Claim(RecoveryLease.ownerId("worker"), start.plusSeconds(10)),
            ),
        )

        val claimed = apply(created, LedgerCommand.Claim("ui", start.plusSeconds(10)))
        at = start.plusSeconds(10)
        assertEquals(
            LedgerRejection.INVALID_TRANSITION,
            rejection(
                claimed,
                LedgerCommand.TakeOver(claimed.ownership, "plain-owner", at.plusSeconds(30)),
            ),
        )
        assertEquals(
            LedgerRejection.INVALID_TRANSITION,
            rejection(
                claimed,
                LedgerCommand.TakeOver(claimed.ownership, RecoveryLease.ownerId("recovery"), at),
            ),
        )
        assertTrue(runCatching { RecoveryLease.ownerId(RecoveryLease.ownerId("nested")) }.isFailure)
    }

    @Test
    fun recoveryOnlyLeaseMayVerifyBusinessFactsButCannotRerunExecution() {
        val claimed = apply(created, LedgerCommand.Claim("ui", start.plusSeconds(10)))
        val token = requireNotNull(claimed.leaseToken())
        val running = apply(claimed, LedgerCommand.Transition(token, RuntimeState.RUNNING))
        at = start.plusSeconds(20)
        val recovery = apply(running, LedgerCommand.TakeOver(running.ownership, RecoveryLease.ownerId("recovery"), at.plusSeconds(30)))
        val recoveryToken = requireNotNull(recovery.leaseToken())
        assertTrue(recoveryToken.recoveryOnly)
        assertEquals(RuntimeState.RUNNING, recovery.state)

        assertEquals(LedgerRejection.RECOVERY_ONLY, rejection(recovery, LedgerCommand.Transition(recoveryToken, RuntimeState.PAUSED)))
        assertEquals(LedgerRejection.RECOVERY_ONLY, rejection(recovery, LedgerCommand.Checkpoint(recoveryToken, JsonValue.text("rerun"))))
        assertEquals(LedgerRejection.RECOVERY_ONLY, rejection(recovery, LedgerCommand.Finish(recoveryToken, RuntimeState.COMPLETED)))

        val fact = BusinessCompletion("fact-1", taskId, 1, RuntimeState.COMPLETED, "receipt-1", at)
        val reconciled = apply(recovery, LedgerCommand.Reconcile(fact, recoveryToken))
        assertEquals(RuntimeState.COMPLETED, reconciled.state)
        assertEquals("receipt-1", reconciled.execution.receipt)
        assertNull(reconciled.ownership.ownerId)
        assertEquals(
            LedgerRejection.FACT_CONFLICT,
            rejection(
                reconciled,
                LedgerCommand.Reconcile(fact.copy(factId = "fact-2"), recoveryToken),
            ),
        )
    }

    @Test
    fun takeoverPreservesCancellationAndSettlesCancelledWithoutRerunning() {
        val claimed = apply(created, LedgerCommand.Claim("ui", start.plusSeconds(10)))
        val token = requireNotNull(claimed.leaseToken())
        val requested = apply(apply(claimed, LedgerCommand.Transition(token, RuntimeState.RUNNING)), LedgerCommand.RequestCancel)
        at = start.plusSeconds(20)
        val recovery = apply(requested, LedgerCommand.TakeOver(requested.ownership, RecoveryLease.ownerId("recovery"), at.plusSeconds(30)))
        assertTrue(recovery.execution.cancelRequested)
        assertEquals(RuntimeState.RUNNING, recovery.state)

        val recoveryToken = requireNotNull(recovery.leaseToken())
        assertEquals(LedgerRejection.RECOVERY_ONLY, rejection(recovery, LedgerCommand.Transition(recoveryToken, RuntimeState.PAUSED)))
        assertEquals(LedgerRejection.RECOVERY_ONLY, rejection(recovery, LedgerCommand.Checkpoint(recoveryToken, null)))
        assertEquals(LedgerRejection.RECOVERY_ONLY, rejection(recovery, LedgerCommand.Finish(recoveryToken, RuntimeState.COMPLETED)))
        val cancelled = apply(recovery, LedgerCommand.Finish(recoveryToken, RuntimeState.INTERRUPTED))
        assertEquals(RuntimeState.CANCELLED, cancelled.state)
        assertTrue(cancelled.execution.cancelRequested)
        assertNull(cancelled.ownership.ownerId)
        assertEquals(LedgerRejection.TERMINAL, rejection(cancelled, LedgerCommand.RequestCancel))
    }

    @Test
    fun confirmationRequiredTaskHasNoTakeoverSurfaceAndKeepsItsExecutionBarrier() {
        val claimed = apply(created, LedgerCommand.Claim("ui", start.plusSeconds(10)))
        val token = requireNotNull(claimed.leaseToken())
        val paused = apply(apply(claimed, LedgerCommand.Transition(token, RuntimeState.RUNNING)), LedgerCommand.RequireConfirmation(token))
        assertTrue(paused.execution.confirmationRequired)
        assertNull(paused.ownership.ownerId)
        at = start.plusSeconds(20)
        assertEquals(
            LedgerRejection.STALE_OWNER,
            rejection(
                paused,
                LedgerCommand.TakeOver(claimed.ownership, RecoveryLease.ownerId("recovery"), at.plusSeconds(30)),
            ),
        )

        // The existing recovery claim path still refuses to execute until a business fact resolves it.
        val recovery = apply(paused, LedgerCommand.Claim("recovery", at.plusSeconds(30), allowCancelled = true))
        val recoveryToken = requireNotNull(recovery.leaseToken())
        assertFalse(recoveryToken.recoveryOnly)
        assertEquals(
            LedgerRejection.CONFIRMATION_REQUIRED,
            rejection(recovery, LedgerCommand.Transition(recoveryToken, RuntimeState.RUNNING)),
        )
    }

    @Test
    fun repeatedCancellationKeepsOneTerminalState() {
        val claimed = apply(created, LedgerCommand.Claim("ui", start.plusSeconds(10)))
        val token = requireNotNull(claimed.leaseToken())
        val running = apply(claimed, LedgerCommand.Transition(token, RuntimeState.RUNNING))
        val requested = apply(running, LedgerCommand.RequestCancel)
        assertTrue(apply(requested, LedgerCommand.RequestCancel) === requested)
        val cancelled = apply(requested, LedgerCommand.Finish(token, RuntimeState.COMPLETED))
        assertEquals(RuntimeState.CANCELLED, cancelled.state)
        assertEquals(LedgerRejection.TERMINAL, rejection(cancelled, LedgerCommand.RequestCancel))
        assertEquals(LedgerRejection.TERMINAL, rejection(cancelled, LedgerCommand.RequestCancelGeneration(1)))
        assertEquals(LedgerRejection.TERMINAL, rejection(cancelled, LedgerCommand.Finish(token, RuntimeState.COMPLETED)))
    }

    @Test
    fun recoveryCapabilityIsDerivedFromThePersistedOwnerIdOnly() {
        val claimed = apply(created, LedgerCommand.Claim("ui", start.plusSeconds(10)))
        at = start.plusSeconds(10)
        val taken = apply(claimed, LedgerCommand.TakeOver(claimed.ownership, RecoveryLease.ownerId("recovery"), at.plusSeconds(30)))
        // Simulates a reopen: only the stored owner id survives, there is no in-memory flag.
        val restored = LeaseToken(taskId, taken.ownership.generation, taken.ownership.ownerEpoch, requireNotNull(taken.ownership.ownerId))
        assertTrue(restored.recoveryOnly)
        assertEquals("recovery", RecoveryLease.logicalOwner(restored.ownerId))
        assertFalse(requireNotNull(claimed.leaseToken()).recoveryOnly)
    }

    private fun apply(snapshot: RuntimeSnapshot, command: LedgerCommand): RuntimeSnapshot {
        val result = RuntimeStateMachine.decide(snapshot, command, at).result
        return when (result) {
            is LedgerResult.Applied -> result.snapshot
            is LedgerResult.Unchanged -> result.snapshot
            is LedgerResult.Rejected -> throw AssertionError("unexpected rejection: ${result.reason}")
            LedgerResult.Missing -> throw AssertionError("unexpected missing")
        }
    }

    private fun rejection(snapshot: RuntimeSnapshot, command: LedgerCommand): LedgerRejection =
        (RuntimeStateMachine.decide(snapshot, command, at).result as LedgerResult.Rejected).reason
}
