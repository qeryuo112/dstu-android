package com.deepstudent.services.runtime.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.services.runtime.api.CapabilityBinding
import com.deepstudent.services.runtime.api.CapabilityBlock
import com.deepstudent.services.runtime.api.CapabilityBudget
import com.deepstudent.services.runtime.api.CapabilityOutcome
import com.deepstudent.services.runtime.api.RuntimeApprovalState
import com.deepstudent.services.runtime.api.RuntimeProjectionChange
import com.deepstudent.services.runtime.api.RuntimeRecordKey
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.backend.CapabilityBroker
import com.deepstudent.services.runtime.backend.HandlerRegistry
import com.deepstudent.services.runtime.backend.LeaseManager
import com.deepstudent.services.runtime.backend.RuntimeController
import com.deepstudent.services.runtime.backend.TaskIdSource
import com.deepstudent.services.runtime.backend.TaskSupervisor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class CapabilityBrokerLedgerTest {
    @get:Rule val temp = TemporaryFolder()
    private val clock = SystemWallClock
    private val kind = TaskKind.parse("test.capability")
    private val taskId = TaskId.parse("capability-test")
    private val key = RuntimeRecordKey::parse
    private val binding = CapabilityBinding(
        key("caller"),
        key("APP"),
        10123,
        key("work"),
        "a".repeat(64),
        CapabilityBudget(10_000, 4096),
    )

    @Test fun denialAndDuplicateDecisionNeverDispatch(): Unit = withRuntime { fixture ->
        val invocations = AtomicInteger()
        val run = fixture.start { invocations.incrementAndGet() }
        val ticket = run.ticket()
        // Both collectors load from SQLite; no callback belongs to either UI collector.
        assertEquals(ticket, run.ticket())
        assertEquals(binding, ticket.binding)
        assertEquals(RuntimeState.WAITING_APPROVAL, run.controller.snapshot(taskId)?.state)
        assertFalse(run.broker.decide(ticket.address.copy(id = key("cap.stale")), true))
        assertFalse(run.broker.decide(ticket.address.copy(task = ticket.address.task.copy(generation = 2)), true))
        assertTrue(run.broker.decide(ticket.address, false))
        assertFalse(run.broker.decide(ticket.address, true))
        assertEquals(CapabilityOutcome.Blocked(CapabilityBlock.DENIED), run.outcome.await())
        assertEquals(0, invocations.get())
        assertEquals(RuntimeApprovalState.DENIED, fixture.ledger.approvals(ticket.address.task, null, 5).single().state)
    }

    @Test fun durableRevokeCancelsAndWaitsForTheOwnerBeforeReturning(): Unit = withRuntime { fixture ->
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val run = fixture.start {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val ticket = run.ticket()
        assertTrue(run.broker.decide(ticket.address, true))
        started.await()
        assertTrue(run.broker.revoke(ticket.address.task))
        assertTrue(stopped.isCompleted)
        assertFalse(run.broker.revoke(ticket.address.task))
        fixture.openChat().use { assertEquals(1L, SqlCapabilityEpochStore(it).current(taskId).task) }
        val codes = fixture.ledger.audit(ticket.address.task, TaskCursor.ZERO, 20).map { it.code.value }
        assertEquals(listOf("shell.test", "APPROVED", "authorized", "revoked", "cancelled"), codes)
    }

    @Test fun epochChangeBetweenRequestAndDecisionFailsClosedAndExpires(): Unit = withRuntime { fixture ->
        val invokes = AtomicInteger()
        val run = fixture.start(500) { invokes.incrementAndGet() }
        val ticket = run.ticket()
        fixture.chat.execute("INSERT INTO revocation_epochs(kind, task_id, epoch, updated_at) VALUES ('global','',1,'test')")
        assertFalse(run.broker.decide(ticket.address, true))
        assertEquals(CapabilityOutcome.Blocked(CapabilityBlock.EXPIRED), run.outcome.await())
        assertEquals(0, invokes.get())
        assertEquals(RuntimeApprovalState.CANCELLED, fixture.ledger.approvals(ticket.address.task, null, 5).single().state)
    }

    @Test fun v3ApprovalsSurviveMigrationAndColdOwnerDoesNotRestoreExecution(): Unit = withRuntime { fixture ->
        fixture.ledger.create(taskId, TaskSubmission(kind), clock.now())
        val task = com.deepstudent.services.runtime.api.RuntimeTaskGeneration(taskId, 1)
        fixture.ledger.apply(task, RuntimeProjectionChange.RequestApproval(key("legacy"), key("legacy.op")), clock.now())
        fixture.database.execute("DROP INDEX runtime_pending_capabilities")
        fixture.database.execute("ALTER TABLE runtime_approvals DROP COLUMN capability_binding")
        fixture.database.execute("UPDATE runtime_schema SET version = 3")
        val migrated = SqlTaskLedger(fixture.database)
        migrated.apply(task, RuntimeProjectionChange.RequestApproval(key("cap.lost"), key("shell.test"), binding), clock.now())
        migrated.closePreviousCapabilityRequests(clock.now())
        val rows = migrated.approvals(task, null, 10).associateBy { it.id.value }
        assertEquals(RuntimeApprovalState.PENDING, rows.getValue("legacy").state)
        assertEquals(RuntimeApprovalState.CANCELLED, rows.getValue("cap.lost").state)
        assertEquals(binding, rows.getValue("cap.lost").binding)
        val codes = migrated.audit(task, TaskCursor.ZERO, 20).map { it.code.value }
        assertTrue("owner.lost" in codes)
        assertFalse("authorized" in codes)
    }

    private fun withRuntime(test: suspend (Fixture) -> Unit) = runBlocking {
        DatabaseFactory.openLocal(temp.root.resolve("runtime.db")).use { database ->
            val fixture = Fixture(database)
            fixture.chat.use {
                try {
                    withTimeout(10_000) { test(fixture) }
                } finally {
                    fixture.job.cancelAndJoin()
                }
            }
        }
    }

    private inner class Fixture(val database: com.deepstudent.platform.database.SqlDatabase) {
        val chat = openChat()
        val ledger = SqlTaskLedger(database)
        val job = SupervisorJob()

        fun openChat(): com.deepstudent.platform.database.SqlDatabase {
            val root = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .first { it.resolve("config/schema/final-lock.json").isFile }
            return DatabaseFactory.openShared(
                temp.root.resolve("chat_v2.db"),
                SharedDatabase.CHAT_V2,
                MigrationSource { root.resolve(it).readBytes() },
            )
        }

        suspend fun start(confirmation: Long = 8_000, invoke: suspend () -> Int): Run {
            val broker = CapabilityBroker(ledger, ledger, SqlCapabilityEpochStore(chat), clock, confirmation)
            val result = CompletableDeferred<CapabilityOutcome<Int>>()
            val supervisor = TaskSupervisor(
                kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.Default),
                ledger,
                LeaseManager(ledger, clock, "test", Duration.ofSeconds(30)),
                HandlerRegistry(
                    mapOf(
                        kind to com.deepstudent.services.runtime.api.RuntimeTaskHandler { context, _ ->
                            result.complete(broker.execute(context, key("shell.test"), binding, perform = invoke))
                        },
                    ),
                ),
                clock,
                com.deepstudent.core.common.logging.NoopLogger,
            )
            val controller = RuntimeController(ledger, supervisor, TaskIdSource { taskId }, clock)
            controller.submit(TaskSubmission(kind))
            return Run(broker, controller, result)
        }
    }

    private data class Run(
        val broker: CapabilityBroker,
        val controller: RuntimeController,
        val outcome: CompletableDeferred<CapabilityOutcome<Int>>,
    ) {
        suspend fun ticket() = broker.pending().first { it.isNotEmpty() }.single()
    }
}
