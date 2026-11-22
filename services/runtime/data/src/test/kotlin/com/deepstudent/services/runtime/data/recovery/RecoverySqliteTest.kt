package com.deepstudent.services.runtime.data.recovery

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.DeadOwnerPredicate
import com.deepstudent.services.runtime.api.RecoveryDecision
import com.deepstudent.services.runtime.api.RecoveryHandler
import com.deepstudent.services.runtime.api.RecoveryPublisher
import com.deepstudent.services.runtime.api.RecoveryRegistration
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskHandler
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.backend.HandlerRegistry
import com.deepstudent.services.runtime.backend.LeaseManager
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.TaskLedger
import com.deepstudent.services.runtime.backend.TaskSupervisor
import com.deepstudent.services.runtime.backend.application.recovery.RecoveryActions
import com.deepstudent.services.runtime.backend.application.recovery.RecoveryCoordinator
import com.deepstudent.services.runtime.backend.leaseToken
import com.deepstudent.services.runtime.data.SqlTaskLedger
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverySqliteTest {
    private val now = Instant.parse("2026-11-16T00:00:00Z")
    private val kind = TaskKind.parse("test.recovery")

    @Test fun reopenReconcilesFactAndPreservesPartialTextWithoutDuplicateEvent() = runBlocking {
        val dir = Files.createTempDirectory("s1-10-recovery").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val file = dir.resolve("runtime.db")
            val facts = Facts()
            val firstId = TaskId.parse("a-fact")
            val secondId = TaskId.parse("b-stream")
            lateinit var old: RuntimeSnapshot
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                old = running(ledger, firstId, "first text")
                running(ledger, secondId, "partial text")
                facts.fact = BusinessCompletion("business-1", firstId, 1, RuntimeState.COMPLETED, "receipt", now)
            }
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                val coordinator = coordinator(ledger, facts, scope)
                val result = coordinator.recover()
                assertEquals(1, result.reconciled)
                assertEquals(1, result.interrupted)
                assertEquals(RuntimeState.COMPLETED, ledger.snapshot(firstId)?.state)
                assertEquals(RuntimeState.INTERRUPTED, ledger.snapshot(secondId)?.state)
                assertEquals(JsonValue.text("partial text"), ledger.snapshot(secondId)?.execution?.checkpoint)
                assertTrue(facts.acknowledged)
                val stale = ledger.apply(firstId, LedgerCommand.Checkpoint(requireNotNull(old.leaseToken()), JsonValue.text("bad")), now.plusSeconds(60))
                assertTrue(stale is LedgerResult.Rejected)
            }
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                val before = ledger.events(firstId, TaskCursor.ZERO).count { it.type.name == "RECONCILED" }
                coordinator(ledger, facts, scope).recover()
                assertEquals(before, ledger.events(firstId, TaskCursor.ZERO).count { it.type.name == "RECONCILED" })
                assertEquals(JsonValue.text("partial text"), ledger.snapshot(secondId)?.execution?.checkpoint)
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test fun realDatabaseKeepsFactWhenProjectionFailsAndRetries() = runBlocking {
        val dir = Files.createTempDirectory("s1-10-retry").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            DatabaseFactory.openLocal(dir.resolve("runtime.db")).use { database ->
                val actual = SqlTaskLedger(database)
                val taskId = TaskId.parse("retry")
                running(actual, taskId, "checkpoint")
                val facts = Facts().apply {
                    fact = BusinessCompletion("business-2", taskId, 1, RuntimeState.COMPLETED, "receipt", now)
                }
                val broken = object : TaskLedger by actual {
                    override suspend fun apply(taskId: TaskId, command: LedgerCommand, now: Instant): LedgerResult {
                        if (command is LedgerCommand.Reconcile) error("private failure")
                        return actual.apply(taskId, command, now)
                    }
                }
                assertTrue(coordinator(broken, facts, scope).recover().failures > 0)
                assertFalse(facts.acknowledged)
                assertEquals(RuntimeState.RUNNING, actual.snapshot(taskId)?.state)
                coordinator(actual, facts, scope).recover()
                assertTrue(facts.acknowledged)
                assertEquals(RuntimeState.COMPLETED, actual.snapshot(taskId)?.state)
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test fun confirmationMarkerAndCheckpointSurviveDatabaseReopen() = runBlocking {
        val dir = Files.createTempDirectory("s1-10-confirm").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val file = dir.resolve("runtime.db")
            val taskId = TaskId.parse("unknown")
            val facts = Facts()
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                running(ledger, taskId, "partial")
                val result = coordinator(ledger, facts, scope, RecoveryDecision.ConfirmationRequired).recover()
                assertEquals(1, result.confirmationRequired)
            }
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                val snapshot = requireNotNull(ledger.snapshot(taskId))
                assertEquals(RuntimeState.PAUSED, snapshot.state)
                assertTrue(snapshot.execution.confirmationRequired)
                assertEquals(JsonValue.text("partial"), snapshot.execution.checkpoint)
                coordinator(ledger, facts, scope, RecoveryDecision.Resume).recover()
                assertTrue(requireNotNull(ledger.snapshot(taskId)).execution.confirmationRequired)
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    private suspend fun running(ledger: TaskLedger, taskId: TaskId, text: String): RuntimeSnapshot {
        ledger.create(taskId, TaskSubmission(kind), now)
        val claim = ledger.apply(taskId, LedgerCommand.Claim("old", now.plusSeconds(20)), now) as LedgerResult.Applied
        val token = requireNotNull(claim.snapshot.leaseToken())
        ledger.apply(taskId, LedgerCommand.Transition(token, RuntimeState.RUNNING), now)
        ledger.apply(taskId, LedgerCommand.Checkpoint(token, JsonValue.text(text)), now)
        return requireNotNull(ledger.snapshot(taskId))
    }

    private fun coordinator(
        ledger: TaskLedger,
        facts: Facts,
        scope: CoroutineScope,
        decision: RecoveryDecision = RecoveryDecision.Interrupted,
    ): RecoveryCoordinator {
        val clock = WallClock { now.plusSeconds(60) }
        val handlers = HandlerRegistry(mapOf(kind to RuntimeTaskHandler { _, _ -> }), mapOf(kind to facts))
        val leases = LeaseManager(ledger, clock, "recovery", Duration.ofMinutes(2))
        val supervisor = TaskSupervisor(scope, ledger, leases, handlers, clock, NoopLogger)
        return RecoveryCoordinator(
            RecoveryActions(ledger, supervisor, clock, "recovery", Duration.ofMinutes(2), DeadOwnerPredicate { true }),
            listOf(RecoveryRegistration(kind, facts, RecoveryHandler { decision })),
            RecoveryPublisher { }, NoopLogger, MonotonicClock { 0 },
        )
    }

    private class Facts : BusinessCompletionSource {
        var fact: BusinessCompletion? = null
        var acknowledged = false
        override suspend fun find(taskId: TaskId, generation: Long): BusinessCompletion? =
            fact?.takeIf { it.taskId == taskId && it.generation == generation }
        override suspend fun pending(consumerId: String, limit: Int): List<BusinessCompletion> =
            if (acknowledged) emptyList() else listOfNotNull(fact)
        override suspend fun acknowledge(consumerId: String, factId: String) { acknowledged = true }
    }
}
