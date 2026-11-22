package com.deepstudent.services.runtime.backend.application.recovery

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.runtime.api.RecoveryDecision
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerRejection
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.leaseToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCoordinatorTest {
    @Test fun committedBusinessFactIsReconciledOnceAndPublished() = runBlocking {
        RecoveryFixture().use { f ->
            val old = f.running("done")
            val fact = f.complete("done")
            val coordinator = f.coordinator()
            val report = coordinator.recover()
            assertEquals(1, report.reconciled)
            assertEquals(RuntimeState.COMPLETED, f.ledger.snapshot(old.taskId)?.state)
            assertEquals(fact.receipt, f.ledger.snapshot(old.taskId)?.execution?.receipt)
            assertTrue(fact.factId in f.facts.acknowledged)
            assertEquals(f.ledger.snapshot(old.taskId), f.published.last())
            val reconciled = f.ledger.events(old.taskId, TaskCursor.ZERO).count { it.type.name == "RECONCILED" }
            coordinator.recover()
            assertEquals(reconciled, f.ledger.events(old.taskId, TaskCursor.ZERO).count { it.type.name == "RECONCILED" })
            val stale = f.ledger.apply(old.taskId, LedgerCommand.Checkpoint(requireNotNull(old.leaseToken()), JsonValue.text("stale")), f.clock.now())
            assertTrue(stale is LedgerResult.Rejected)
        }
    }

    @Test fun interruptedOutputAndTasksAreIsolated() = runBlocking {
        RecoveryFixture().use { f ->
            val interrupted = f.running("a", "partial secret")
            val completed = f.running("b", "other content")
            f.complete("b")
            val report = f.coordinator().recover()
            assertEquals(1, report.interrupted)
            assertEquals(RuntimeState.INTERRUPTED, f.ledger.snapshot(interrupted.taskId)?.state)
            assertEquals(JsonValue.text("partial secret"), f.ledger.snapshot(interrupted.taskId)?.execution?.checkpoint)
            assertEquals(RuntimeState.COMPLETED, f.ledger.snapshot(completed.taskId)?.state)
            assertFalse(f.logs.toString().contains("partial secret"))
            assertFalse(f.logs.toString().contains("other content"))
            assertFalse(f.logs.toString().contains("receipt"))
        }
    }

    @Test fun liveUnknownOwnerIsNotFencedBeforeExpiry() = runBlocking {
        RecoveryFixture().use { f ->
            val old = f.running("active")
            val renewed = f.ledger.apply(old.taskId, LedgerCommand.Renew(requireNotNull(old.leaseToken()),
                fixtureTime.plusSeconds(120)), fixtureTime) as LedgerResult.Applied
            val result = f.coordinator(dead = false).recover()
            assertEquals(1, result.blocked)
            assertEquals(renewed.snapshot, f.ledger.snapshot(old.taskId))
        }
    }

    @Test fun expiredLeaseIsFencedWithinTheLedgerTransaction() = runBlocking {
        RecoveryFixture().use { f ->
            val old = f.running("expired")
            assertEquals(1, f.coordinator(dead = false).recover().interrupted)
            assertEquals(RuntimeState.INTERRUPTED, f.ledger.snapshot(old.taskId)?.state)
        }
    }

    @Test fun failureNeverAcknowledgesBusinessFact() = runBlocking {
        RecoveryFixture().use { f ->
            val task = f.running("failure")
            val fact = f.complete("failure")
            f.ledger.failReconcile = true
            val report = f.coordinator().recover()
            assertTrue(report.failures > 0)
            assertFalse(fact.factId in f.facts.acknowledged)
            assertEquals(fact, f.facts.facts[task.taskId])
            assertFalse(f.logs.toString().contains("private SQL error"))
            f.ledger.failReconcile = false
            f.coordinator().recover()
            assertEquals(RuntimeState.COMPLETED, f.ledger.snapshot(task.taskId)?.state)
        }
    }

    @Test fun unavailablePendingBlocksAllTasksOfThatSourceEvenWhenFindWouldReturnNull() = runBlocking {
        RecoveryFixture().use { f ->
            val first = f.running("a")
            val second = f.running("b")
            f.facts.failPending = true
            val report = f.coordinator().recover()
            assertEquals(2, report.blocked)
            assertEquals(0, report.interrupted)
            assertEquals(RuntimeState.RUNNING, f.ledger.snapshot(first.taskId)?.state)
            assertEquals(RuntimeState.RUNNING, f.ledger.snapshot(second.taskId)?.state)
            assertFalse(f.logs.toString().contains("private outbox error"))
        }
    }

    @Test fun failedFindDoesNotResumeOrInterrupt() = runBlocking {
        RecoveryFixture().use { f ->
            val task = f.running("lookup")
            f.facts.failLookup = true
            val report = f.coordinator(RecoveryDecision.Resume).recover()
            assertEquals(1, report.blocked)
            assertEquals(0, f.executions.get())
            assertEquals(RuntimeState.RUNNING, f.ledger.snapshot(task.taskId)?.state)
            assertFalse(f.logs.toString().contains("private business text"))
        }
    }

    @Test fun safeResumeTransfersClaimToRegisteredSupervisorHandler() = runBlocking {
        RecoveryFixture().use { f ->
            val task = f.running("resume")
            val report = f.coordinator(RecoveryDecision.Resume).recover()
            assertEquals(1, report.resumed)
            assertEquals(1, f.executions.get())
            assertEquals(JsonValue.text("partial"), f.ledger.snapshot(task.taskId)?.execution?.checkpoint)
        }
    }

    @Test fun failedAcknowledgementRetriesWithoutDuplicateProjection() = runBlocking {
        RecoveryFixture().use { f ->
            val task = f.running("ack")
            f.complete("ack")
            f.facts.failAck = true
            val coordinator = f.coordinator()
            coordinator.recover()
            assertEquals(RuntimeState.COMPLETED, f.ledger.snapshot(task.taskId)?.state)
            val count = f.ledger.events(task.taskId, TaskCursor.ZERO).count { it.type.name == "RECONCILED" }
            f.facts.failAck = false
            coordinator.recover()
            assertEquals(count, f.ledger.events(task.taskId, TaskCursor.ZERO).count { it.type.name == "RECONCILED" })
            assertTrue("fact-ack" in f.facts.acknowledged)
        }
    }

    @Test fun unknownSideEffectIsNotAutomaticallyRun() = runBlocking {
        RecoveryFixture().use { f ->
            val task = f.running("unknown")
            val coordinator = f.coordinator(RecoveryDecision.ConfirmationRequired)
            val result = coordinator.recover()
            assertEquals(1, result.confirmationRequired)
            assertEquals(RuntimeState.PAUSED, f.ledger.snapshot(task.taskId)?.state)
            assertTrue(requireNotNull(f.ledger.snapshot(task.taskId)).execution.confirmationRequired)
            assertEquals(JsonValue.text("partial"), f.ledger.snapshot(task.taskId)?.execution?.checkpoint)
            coordinator.recover()
            assertTrue(requireNotNull(f.ledger.snapshot(task.taskId)).execution.confirmationRequired)
        }
    }

    @Test fun laterBusinessFactResolvesConfirmationWithoutExecutingAgain() = runBlocking {
        RecoveryFixture().use { f ->
            val task = f.running("confirm")
            val coordinator = f.coordinator(RecoveryDecision.ConfirmationRequired)
            coordinator.recover()
            assertTrue(requireNotNull(f.ledger.snapshot(task.taskId)).execution.confirmationRequired)
            f.complete("confirm")
            coordinator.recover()
            val snapshot = requireNotNull(f.ledger.snapshot(task.taskId))
            assertEquals(RuntimeState.COMPLETED, snapshot.state)
            assertFalse(snapshot.execution.confirmationRequired)
            assertEquals(JsonValue.text("partial"), snapshot.execution.checkpoint)
        }
    }

    @Test fun concurrentRecoverCallsAreSerialized() = runBlocking {
        RecoveryFixture().use { f ->
            f.running("serial")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.facts.beforeFind = { entered.complete(Unit); release.await() }
            val coordinator = f.coordinator()
            val first = async { coordinator.recover() }
            entered.await()
            val second = async { coordinator.recover() }
            assertFalse(second.isCompleted)
            release.complete(Unit)
            assertEquals(1, first.await().interrupted)
            assertEquals(0, second.await().interrupted)
        }
    }

    @Test fun cancellationSurvivesCleanupFatalError() = runBlocking {
        RecoveryFixture().use { f ->
            f.running("cancel")
            val cancellation = CancellationException("private cancellation")
            val fatal = AssertionError("private cleanup")
            f.facts.beforeFind = { throw cancellation }
            f.ledger.cleanupError = fatal
            val thrown = try { f.coordinator().recover(); null } catch (caught: Throwable) { caught }
            assertSame(cancellation, thrown)
            val cleanup = thrown!!.suppressed.single()
            // Coroutine stack-trace recovery may copy the Error while preserving its type and message.
            assertEquals(fatal.javaClass, cleanup.javaClass)
            assertEquals(fatal.message, cleanup.message)
        }
    }

    @Test fun staleOwnerCannotWriteAfterRecoveryClaim() = runBlocking {
        RecoveryFixture().use { f ->
            val old = f.running("fence")
            val candidate = RecoverySession(
                RecoveryActions(f.ledger, f.supervisor, f.clock, "recovery", java.time.Duration.ofMinutes(2),
                    com.deepstudent.services.runtime.api.DeadOwnerPredicate { true }),
                RecoveryRegistry(emptyList()), com.deepstudent.services.runtime.api.RecoveryPublisher { },
                RecoveryLog(f.logger, com.deepstudent.core.common.time.MonotonicClock { 0 }), 10,
            )
            val claim = requireNotNull(RecoveryClaims(candidate).claim(old))
            val result = f.ledger.apply(old.taskId, LedgerCommand.Checkpoint(requireNotNull(old.leaseToken()), JsonValue.text("bad")), f.clock.now())
            assertEquals(LedgerRejection.STALE_OWNER, (result as LedgerResult.Rejected).reason)
            assertEquals(JsonValue.text("partial"), claim.execution.checkpoint)
        }
    }
}
