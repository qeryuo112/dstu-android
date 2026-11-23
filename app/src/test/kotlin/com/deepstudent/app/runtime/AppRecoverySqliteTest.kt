package com.deepstudent.app.runtime

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.features.chat.backend.application.ChatCheckpointSource
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.DeadOwnerPredicate
import com.deepstudent.services.runtime.api.RecoveryPublisher
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import com.deepstudent.services.runtime.api.RuntimeTaskHandler
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.backend.HandlerRegistry
import com.deepstudent.services.runtime.backend.LeaseManager
import com.deepstudent.services.runtime.backend.LeaseToken
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.TaskSupervisor
import com.deepstudent.services.runtime.backend.application.recovery.RecoveryActions
import com.deepstudent.services.runtime.backend.application.recovery.RecoveryCoordinator
import com.deepstudent.services.runtime.backend.leaseToken
import com.deepstudent.services.runtime.data.SqlTaskLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * S2.22 against the real bundled runtime.db. Each test writes with one SqlTaskLedger owner and
 * reopens the file before recovering, so the fact check and the gate decision run on persisted
 * state. Business facts are fixtures; notes.create idempotency itself is covered by
 * NoteTaskStoreTest, while here the same task must never execute twice across restarts.
 */
class AppRecoverySqliteTest {
    private val root = Files.createTempDirectory("s222-app-recovery").toFile()
    private val file = File(root, "runtime.db")
    private val start = Instant.parse("2026-11-23T00:00:00Z")
    private val lease = Duration.ofSeconds(30)

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun reopenProjectsTheBusinessFactBeforeAnyResumeDecision() = runBlocking {
        val world = World()
        open(world, at = start.plusSeconds(10)).use { first ->
            world.facts.complete(first.running(AppRecoverableTask.NOTE_CREATE, "fact-first"))
        }
        open(world, at = start.plusSeconds(60)).use { second ->
            val report = second.coordinator.recover()
            assertEquals(1, report.reconciled)
            assertEquals(0, report.resumed)
            assertEquals(0, world.executions.getValue(AppRecoverableTask.NOTE_CREATE))
            val snapshot = requireNotNull(second.ledger.snapshot(TaskId.parse("fact-first")))
            assertEquals(RuntimeState.COMPLETED, snapshot.state)
            assertEquals("receipt-fact-first", snapshot.execution.receipt)
            assertTrue(world.facts.acknowledged.contains("fact-fact-first"))
            assertEquals(
                1,
                second.ledger.events(TaskId.parse("fact-first"), TaskCursor.ZERO)
                    .count { it.type.name == "RECONCILED" },
            )
        }
        open(world, at = start.plusSeconds(120)).use { third ->
            third.coordinator.recover()
            assertEquals(0, world.executions.getValue(AppRecoverableTask.NOTE_CREATE))
            assertEquals(
                1,
                third.ledger.events(TaskId.parse("fact-first"), TaskCursor.ZERO)
                    .count { it.type.name == "RECONCILED" },
            )
        }
    }

    @Test
    fun notesCreateResumesOnceAndRepeatedRecoveryNeverExecutesAgain() = runBlocking {
        val world = World()
        open(world, at = start.plusSeconds(10)).use { first ->
            first.running(AppRecoverableTask.NOTE_CREATE, "note-resume", checkpoint = JsonValue.text("half written"))
        }
        open(world, at = start.plusSeconds(60)).use { second ->
            val report = second.coordinator.recover()
            assertEquals(1, report.resumed)
            val snapshot = awaitTerminal(second.ledger, "note-resume")
            assertEquals(RuntimeState.COMPLETED, snapshot.state)
            assertEquals("note-from-handler", snapshot.execution.receipt)
            assertEquals(JsonValue.text("half written"), snapshot.execution.checkpoint)
            assertEquals(1, world.executions.getValue(AppRecoverableTask.NOTE_CREATE))
            assertEquals(0, second.coordinator.recover().resumed)
            assertEquals(1, world.executions.getValue(AppRecoverableTask.NOTE_CREATE))
        }
        open(world, at = start.plusSeconds(300)).use { third ->
            third.coordinator.recover()
            assertEquals(1, world.executions.getValue(AppRecoverableTask.NOTE_CREATE))
        }
    }

    @Test
    fun streamImportAndChatKeepTheirPartialCheckpointsAsInterrupted() = runBlocking {
        val world = World()
        open(world, at = start.plusSeconds(10)).use { first ->
            first.running(AppRecoverableTask.NOTE_IMPORT, "import", checkpoint = JsonValue.text("partial import"))
            first.running(AppRecoverableTask.CHAT, "chat", checkpoint = JsonValue.text("partial chat"))
        }
        open(world, at = start.plusSeconds(60)).use { second ->
            val report = second.coordinator.recover()
            assertEquals(2, report.interrupted)
            assertEquals(0, report.resumed)
            assertEquals(0, world.executions.getValue(AppRecoverableTask.NOTE_IMPORT))
            assertEquals(0, world.executions.getValue(AppRecoverableTask.CHAT))
            assertCheckpoint(second.ledger, "import", "partial import", RuntimeState.INTERRUPTED)
            assertCheckpoint(second.ledger, "chat", "partial chat", RuntimeState.INTERRUPTED)
            assertEquals(0, second.coordinator.recover().interrupted)
            assertCheckpoint(second.ledger, "import", "partial import", RuntimeState.INTERRUPTED)
        }
        open(world, at = start.plusSeconds(300)).use { third ->
            third.coordinator.recover()
            assertCheckpoint(third.ledger, "chat", "partial chat", RuntimeState.INTERRUPTED)
            assertEquals(0, world.executions.getValue(AppRecoverableTask.CHAT))
        }
    }

    @Test
    fun scriptWithoutReceiptWaitsForConfirmationAndNeverReruns() = runBlocking {
        val world = World()
        open(world, at = start.plusSeconds(10)).use { first ->
            first.running(
                AppRecoverableTask.SCRIPT,
                "script",
                checkpoint = JsonValue.obj("stage" to JsonValue.text("running")),
            )
        }
        open(world, at = start.plusSeconds(60)).use { second ->
            val report = second.coordinator.recover()
            assertEquals(1, report.confirmationRequired)
            assertEquals(0, report.resumed)
            assertEquals(0, world.executions.getValue(AppRecoverableTask.SCRIPT))
            val snapshot = requireNotNull(second.ledger.snapshot(TaskId.parse("script")))
            assertEquals(RuntimeState.PAUSED, snapshot.state)
            assertTrue(snapshot.execution.confirmationRequired)
            assertEquals(JsonValue.obj("stage" to JsonValue.text("running")), snapshot.execution.checkpoint)
            second.coordinator.recover()
            assertEquals(0, world.executions.getValue(AppRecoverableTask.SCRIPT))
            assertTrue(requireNotNull(second.ledger.snapshot(TaskId.parse("script"))).execution.confirmationRequired)
        }
    }

    @Test
    fun liveOwnerIsNotFencedAndGetsNoExecution() = runBlocking {
        val world = World()
        open(world, at = start.plusSeconds(10)).use { first ->
            first.running(
                AppRecoverableTask.NOTE_CREATE,
                "live",
                checkpoint = JsonValue.text("live work"),
                leaseUntil = start.plusSeconds(120),
            )
        }
        open(world, at = start.plusSeconds(60), dead = false).use { second ->
            val report = second.coordinator.recover()
            assertEquals(1, report.blocked)
            assertEquals(0, report.resumed)
            assertEquals(0, world.executions.getValue(AppRecoverableTask.NOTE_CREATE))
            val snapshot = requireNotNull(second.ledger.snapshot(TaskId.parse("live")))
            assertEquals(RuntimeState.RUNNING, snapshot.state)
            assertEquals("main:old-owner", snapshot.ownership.ownerId)
            assertEquals(1L, snapshot.ownership.ownerEpoch)
            assertEquals(JsonValue.text("live work"), snapshot.execution.checkpoint)
        }
    }

    @Test
    fun expiredLeaseIsFencedButOnlyTheIdempotentNoteCreateRetries() = runBlocking {
        val world = World()
        open(world, at = start.plusSeconds(10)).use { first ->
            first.running(AppRecoverableTask.NOTE_CREATE, "expired-note", checkpoint = JsonValue.text("kept"))
            first.running(AppRecoverableTask.NOTE_IMPORT, "expired-import", checkpoint = JsonValue.text("partial"))
        }
        open(world, at = start.plusSeconds(60), dead = false).use { second ->
            val report = second.coordinator.recover()
            assertEquals(1, report.resumed)
            assertEquals(1, report.interrupted)
            val note = awaitTerminal(second.ledger, "expired-note")
            assertEquals(RuntimeState.COMPLETED, note.state)
            assertEquals(JsonValue.text("kept"), note.execution.checkpoint)
            assertEquals(1, world.executions.getValue(AppRecoverableTask.NOTE_CREATE))
            assertEquals(0, world.executions.getValue(AppRecoverableTask.NOTE_IMPORT))
            assertCheckpoint(second.ledger, "expired-import", "partial", RuntimeState.INTERRUPTED)
            val oldToken = LeaseToken(TaskId.parse("expired-note"), 1, 1, "main:old-owner")
            val stale = second.ledger.apply(
                TaskId.parse("expired-note"),
                LedgerCommand.Checkpoint(oldToken, JsonValue.text("late")),
                start.plusSeconds(61),
            )
            assertTrue(stale is LedgerResult.Rejected)
        }
    }

    @Test
    fun approvalAndPausedGatesStayInPlaceWithoutExecuting() = runBlocking {
        val world = World()
        open(world, at = start.plusSeconds(10)).use { first ->
            first.running(AppRecoverableTask.NOTE_CREATE, "approval", gate = RuntimeState.WAITING_APPROVAL)
            first.running(AppRecoverableTask.NOTE_CREATE, "paused", gate = RuntimeState.PAUSED)
        }
        open(world, at = start.plusSeconds(60)).use { second ->
            val report = second.coordinator.recover()
            assertEquals(0, report.resumed)
            assertEquals(0, report.interrupted)
            assertEquals(0, report.confirmationRequired)
            assertEquals(0, world.executions.getValue(AppRecoverableTask.NOTE_CREATE))
            assertGate(second.ledger, "approval", RuntimeState.WAITING_APPROVAL)
            assertGate(second.ledger, "paused", RuntimeState.PAUSED)
            // A gate kept by KeepPaused has no live owner afterwards. Each later pass claims and
            // releases it (LEASE_CHANGED only): never a state change, execution or fake result.
            val before = second.ledger.events(TaskId.parse("approval"), TaskCursor.ZERO)
            second.coordinator.recover()
            val added = second.ledger.events(TaskId.parse("approval"), TaskCursor.ZERO).drop(before.size)
            assertTrue(added.isNotEmpty())
            assertTrue(added.all { it.type.name == "LEASE_CHANGED" })
            assertEquals(0, world.executions.getValue(AppRecoverableTask.NOTE_CREATE))
            assertGate(second.ledger, "approval", RuntimeState.WAITING_APPROVAL)
            assertGate(second.ledger, "paused", RuntimeState.PAUSED)
        }
    }

    @Test
    fun cancelWinsOverAGateInsteadOfResuming() = runBlocking {
        val world = World()
        open(world, at = start.plusSeconds(10)).use { first ->
            first.running(AppRecoverableTask.NOTE_CREATE, "cancelled-gate", gate = RuntimeState.WAITING_APPROVAL)
            first.ledger.apply(TaskId.parse("cancelled-gate"), LedgerCommand.RequestCancel, start.plusSeconds(11))
        }
        open(world, at = start.plusSeconds(60)).use { second ->
            second.coordinator.recover()
            assertEquals(0, world.executions.getValue(AppRecoverableTask.NOTE_CREATE))
            assertEquals(RuntimeState.CANCELLED, second.ledger.snapshot(TaskId.parse("cancelled-gate"))?.state)
        }
    }

    private suspend fun awaitTerminal(ledger: SqlTaskLedger, id: String) = withTimeout(5_000) {
        var snapshot = requireNotNull(ledger.snapshot(TaskId.parse(id)))
        while (!snapshot.state.terminal) {
            delay(10)
            snapshot = requireNotNull(ledger.snapshot(TaskId.parse(id)))
        }
        snapshot
    }

    private suspend fun assertCheckpoint(ledger: SqlTaskLedger, id: String, text: String, state: RuntimeState) {
        val snapshot = requireNotNull(ledger.snapshot(TaskId.parse(id)))
        assertEquals(state, snapshot.state)
        assertEquals(JsonValue.text(text), snapshot.execution.checkpoint)
    }

    private suspend fun assertGate(ledger: SqlTaskLedger, id: String, state: RuntimeState) {
        val snapshot = requireNotNull(ledger.snapshot(TaskId.parse(id)))
        assertEquals(state, snapshot.state)
        assertFalse(snapshot.execution.confirmationRequired)
        assertEquals(JsonValue.text("gate checkpoint"), snapshot.execution.checkpoint)
        assertNull(snapshot.ownership.ownerId)
    }

    private fun open(world: World, at: Instant, dead: Boolean = true): Opened {
        val database = DatabaseFactory.openLocal(file)
        val ledger = SqlTaskLedger(database)
        val clock = WallClock { at }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val handlers = HandlerRegistry(
            mapOf(
                AppRecoverableTask.NOTE_CREATE.kind to noteHandler(world),
                AppRecoverableTask.NOTE_IMPORT.kind to countingHandler(world, AppRecoverableTask.NOTE_IMPORT),
                AppRecoverableTask.CHAT.kind to countingHandler(world, AppRecoverableTask.CHAT),
                AppRecoverableTask.SCRIPT.kind to countingHandler(world, AppRecoverableTask.SCRIPT),
            ),
            mapOf(
                AppRecoverableTask.NOTE_CREATE.kind to world.facts,
                AppRecoverableTask.NOTE_IMPORT.kind to world.facts,
                AppRecoverableTask.CHAT.kind to ChatCheckpointSource,
                AppRecoverableTask.SCRIPT.kind to world.facts,
            ),
        )
        val supervisor = TaskSupervisor(
            scope,
            ledger,
            LeaseManager(ledger, clock, "main:test-owner", lease),
            handlers,
            clock,
            NoopLogger,
        )
        val actions = RecoveryActions(ledger, supervisor, clock, "main:recovery", lease, DeadOwnerPredicate { dead })
        val coordinator = AppRecoveryPlan.coordinator(
            actions,
            world.policy,
            RecoveryPublisher { },
            NoopLogger,
            MonotonicClock { 0 },
        )
        return Opened(database, ledger, scope, coordinator)
    }

    private fun noteHandler(world: World) = RuntimeTaskHandler { context: RuntimeTaskContext, _: JsonValue ->
        world.executions[AppRecoverableTask.NOTE_CREATE] =
            world.executions.getValue(AppRecoverableTask.NOTE_CREATE) + 1
        world.facts.commitNote(context.taskId, context.generation, "note-from-handler")
    }

    private fun countingHandler(world: World, task: AppRecoverableTask) = RuntimeTaskHandler { _: RuntimeTaskContext, _: JsonValue ->
        world.executions[task] = world.executions.getValue(task) + 1
    }

    private class Opened(
        private val database: SqlDatabase,
        val ledger: SqlTaskLedger,
        private val scope: CoroutineScope,
        val coordinator: RecoveryCoordinator,
    ) : AutoCloseable {
        suspend fun running(
            task: AppRecoverableTask,
            id: String,
            checkpoint: JsonValue = JsonValue.text("gate checkpoint"),
            gate: RuntimeState? = null,
            leaseUntil: Instant = Instant.parse("2026-11-23T00:00:20Z"),
        ): TaskId {
            val taskId = TaskId.parse(id)
            ledger.create(taskId, TaskSubmission(task.kind), Instant.parse("2026-11-23T00:00:00Z"))
            val claim = ledger.apply(
                taskId,
                LedgerCommand.Claim("main:old-owner", leaseUntil),
                Instant.parse("2026-11-23T00:00:00Z"),
            ) as LedgerResult.Applied
            val token = requireNotNull(claim.snapshot.leaseToken())
            ledger.apply(
                taskId,
                LedgerCommand.Transition(token, RuntimeState.RUNNING),
                Instant.parse("2026-11-23T00:00:01Z"),
            )
            ledger.apply(
                taskId,
                LedgerCommand.Checkpoint(token, checkpoint),
                Instant.parse("2026-11-23T00:00:01Z"),
            )
            if (gate != null) {
                ledger.apply(
                    taskId,
                    LedgerCommand.Transition(token, gate),
                    Instant.parse("2026-11-23T00:00:02Z"),
                )
            }
            return taskId
        }

        override fun close() {
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            database.close()
        }
    }

    private class World {
        val facts = Facts()
        val executions = AppRecoverableTask.entries.associateWith { 0 }.toMutableMap()
        val policy = AppRecoveryPolicy(
            mapOf(
                AppRecoverableTask.NOTE_CREATE to facts,
                AppRecoverableTask.NOTE_IMPORT to facts,
                AppRecoverableTask.CHAT to ChatCheckpointSource,
                AppRecoverableTask.SCRIPT to facts,
            ),
        )
    }

    private class Facts : BusinessCompletionSource {
        private val facts = mutableMapOf<TaskId, BusinessCompletion>()
        val acknowledged = mutableSetOf<String>()

        fun complete(taskId: TaskId): BusinessCompletion = BusinessCompletion(
            "fact-${taskId.value}",
            taskId,
            1,
            RuntimeState.COMPLETED,
            "receipt-${taskId.value}",
            Instant.parse("2026-11-23T00:00:03Z"),
        ).also { facts[taskId] = it }

        fun commitNote(taskId: TaskId, generation: Long, noteId: String): BusinessCompletion = BusinessCompletion(
            "notes:fact-${taskId.value}",
            taskId,
            generation,
            RuntimeState.COMPLETED,
            noteId,
            Instant.parse("2026-11-23T00:00:03Z"),
        ).also { facts[taskId] = it }

        override suspend fun find(taskId: TaskId, generation: Long): BusinessCompletion? =
            facts[taskId]?.takeIf { it.generation == generation }

        override suspend fun pending(consumerId: String, limit: Int): List<BusinessCompletion> =
            facts.values.filter { it.factId !in acknowledged }.take(limit)

        override suspend fun acknowledge(consumerId: String, factId: String) {
            acknowledged.add(factId)
        }
    }
}
