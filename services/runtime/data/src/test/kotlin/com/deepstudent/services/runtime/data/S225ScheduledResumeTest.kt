package com.deepstudent.services.runtime.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.RuntimeCancelResult
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskHandler
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.backend.HandlerRegistry
import com.deepstudent.services.runtime.backend.LeaseManager
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerRejection
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.RuntimeController
import com.deepstudent.services.runtime.backend.TaskIdSource
import com.deepstudent.services.runtime.backend.TaskSupervisor
import com.deepstudent.services.runtime.backend.leaseToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * S2.25 scheduled-run contract against the real bundled SQLite runtime.db.
 *
 * The handler models an idempotent business write (the production `notes.create` shape): it
 * commits a [BusinessCompletion] fact when it finishes. Yielding before that commit must keep
 * the task non-terminal, preserve the checkpoint and let the same task id/generation resume.
 */
class S225ScheduledResumeTest {
    private val start = Instant.parse("2026-11-24T00:00:00Z")
    private val kind = TaskKind.parse("s225.scheduled")
    private val submission = TaskSubmission(kind, JsonValue.text("payload"), dedupeKey = "note:one")

    @Test
    fun sameDedupeKeyRunsOnceAndReplayReturnsDurableCompletion() = runBlocking {
        withWorld { world ->
            val facts = Facts()
            val attempts = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val handler = RuntimeTaskHandler { context, _ ->
                attempts.incrementAndGet()
                entered.complete(Unit)
                gate.await()
                facts.commit(context.taskId, context.generation)
            }
            val controller = world.controller(handler, facts)

            val first = async { controller.runScheduled(submission) }
            entered.await()
            val follower = controller.runScheduled(submission)
            assertEquals(1, attempts.get())
            assertFalse(follower.state.terminal)
            assertFalse(follower.executedHere)

            gate.complete(Unit)
            val settled = first.await()
            assertEquals(RuntimeState.COMPLETED, settled.state)
            assertTrue(settled.executedHere)

            val replay = controller.runScheduled(submission)
            assertEquals(settled.taskId, follower.taskId)
            assertEquals(settled.taskId, replay.taskId)
            assertEquals(settled.generation, replay.generation)
            assertEquals(RuntimeState.COMPLETED, replay.state)
            assertTrue(replay.reusedExisting)
            assertFalse(replay.executedHere)
            assertEquals(1, attempts.get())
            assertEquals(1, facts.acknowledged.size)
        }
    }

    @Test
    fun systemStopYieldsNonTerminallyAndResumesSameTaskFromCheckpoint() = runBlocking {
        withWorld { world ->
            val facts = Facts()
            val attempts = AtomicInteger()
            val firstCancelled = AtomicBoolean()
            val handler = RuntimeTaskHandler { context, _ ->
                if (context.resumeCheckpoint == null) {
                    attempts.incrementAndGet()
                    context.checkpoint(JsonValue.text("half"))
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        firstCancelled.set(true)
                    }
                } else {
                    attempts.incrementAndGet()
                    context.checkpoint(JsonValue.text("done"))
                    facts.commit(context.taskId, context.generation)
                }
            }
            val controller = world.controller(handler, facts)

            val job = world.scope.launch { controller.runScheduled(submission) }
            val running = world.awaitSnapshot { it.execution.checkpoint == JsonValue.text("half") }
            job.cancelAndJoin()
            assertTrue(firstCancelled.get())

            val yielded = requireNotNull(world.ledger.snapshot(running.taskId))
            assertEquals(RuntimeState.RUNNING, yielded.state)
            assertNull(yielded.ownership.ownerId)
            assertEquals(JsonValue.text("half"), yielded.execution.checkpoint)
            assertFalse(yielded.execution.cancelRequested)
            assertTrue(
                world.ledger.events(running.taskId, TaskCursor.ZERO).none {
                    it.state == RuntimeState.CANCELLED
                },
            )

            val resumed = controller.runScheduled(submission)
            assertEquals(running.taskId, resumed.taskId)
            assertEquals(running.ownership.generation, resumed.generation)
            assertEquals(RuntimeState.COMPLETED, resumed.state)
            assertTrue(resumed.executedHere)
            assertEquals(2, attempts.get())
            assertEquals(JsonValue.text("done"), requireNotNull(world.ledger.snapshot(running.taskId)).execution.checkpoint)
        }
    }

    @Test
    fun userCancelAfterReleaseSettlesCancelledWithoutRerunning() = runBlocking {
        withWorld { world ->
            val facts = Facts()
            val attempts = AtomicInteger()
            val handler = RuntimeTaskHandler { context, _ ->
                attempts.incrementAndGet()
                context.checkpoint(JsonValue.text("half"))
                CompletableDeferred<Unit>().await()
                facts.commit(context.taskId, context.generation)
            }
            val controller = world.controller(handler, facts)

            val job = world.scope.launch { controller.runScheduled(submission) }
            val running = world.awaitSnapshot { it.execution.checkpoint == JsonValue.text("half") }
            job.cancelAndJoin()

            val released = requireNotNull(world.ledger.snapshot(running.taskId))
            assertEquals(RuntimeState.RUNNING, released.state)
            assertFalse(released.execution.cancelRequested)
            assertNull(released.ownership.ownerId)
            val cancel = controller.cancel(running.taskId)
            assertTrue(cancel is RuntimeCancelResult.Requested)
            val cancelled = requireNotNull(world.ledger.snapshot(running.taskId))
            assertTrue(cancelled.execution.cancelRequested)
            assertEquals(RuntimeState.CANCELLED, cancelled.state)

            val settled = controller.runScheduled(submission)
            assertEquals(running.taskId, settled.taskId)
            assertEquals(RuntimeState.CANCELLED, settled.state)
            assertFalse(settled.executedHere)
            assertEquals(1, attempts.get())
            assertEquals(JsonValue.text("half"), requireNotNull(world.ledger.snapshot(running.taskId)).execution.checkpoint)
        }
    }

    @Test
    fun yieldedOwnerTokenCannotWriteAfterRelease() = runBlocking {
        withWorld { world ->
            val facts = Facts()
            val handler = RuntimeTaskHandler { context, _ ->
                context.checkpoint(JsonValue.text("half"))
                CompletableDeferred<Unit>().await()
                facts.commit(context.taskId, context.generation)
            }
            val controller = world.controller(handler, facts)

            val job = world.scope.launch { controller.runScheduled(submission) }
            val running = world.awaitSnapshot { it.execution.checkpoint == JsonValue.text("half") }
            val old = requireNotNull(running.leaseToken())
            job.cancelAndJoin()

            val late = world.ledger.apply(running.taskId, LedgerCommand.Checkpoint(old, JsonValue.text("late")), start)
            assertEquals(LedgerRejection.STALE_OWNER, (late as LedgerResult.Rejected).reason)
            val lateRelease = world.ledger.apply(running.taskId, LedgerCommand.Release(old), start)
            assertTrue(lateRelease is LedgerResult.Unchanged)
            assertEquals(JsonValue.text("half"), requireNotNull(world.ledger.snapshot(running.taskId)).execution.checkpoint)
        }
    }

    @Test
    fun pausedScheduledTaskIsNotExecutedByRunScheduled() = runBlocking {
        withWorld { world ->
            val facts = Facts()
            val attempts = AtomicInteger()
            val handler = RuntimeTaskHandler { context, _ ->
                attempts.incrementAndGet()
                facts.commit(context.taskId, context.generation)
            }
            val controller = world.controller(handler, facts)
            val taskId = TaskId.parse("s225-gated")
            world.ledger.create(taskId, submission, start)
            val token = requireNotNull(LeaseManager(world.ledger, world.clock, "ui", Duration.ofSeconds(30)).claim(taskId))
            world.ledger.apply(taskId, LedgerCommand.Transition(token, RuntimeState.RUNNING), start)
            world.ledger.apply(taskId, LedgerCommand.RequireConfirmation(token), start)

            val outcome = controller.runScheduled(submission)
            assertEquals(taskId, outcome.taskId)
            assertEquals(RuntimeState.PAUSED, outcome.state)
            assertFalse(outcome.executedHere)
            assertEquals(0, attempts.get())
        }
    }

    @Test
    fun scheduledKindsRequireExplicitRegistrationAndDedupeKey() = runBlocking {
        withWorld { world ->
            val facts = Facts()
            val handler = RuntimeTaskHandler { _, _ -> }
            val controller = world.controller(handler, facts)
            val other = TaskKind.parse("s225.other")
            assertTrue(
                runCatching { controller.runScheduled(TaskSubmission(other, dedupeKey = "x")) }
                    .exceptionOrNull() is IllegalArgumentException,
            )
            assertTrue(
                runCatching { controller.runScheduled(TaskSubmission(kind)) }
                    .exceptionOrNull() is IllegalArgumentException,
            )
            assertEquals(0, facts.acknowledged.size)
        }
    }

    @Test
    fun concurrentScheduledCallsClaimExactlyOneOwner() = runBlocking {
        withWorld { world ->
            val facts = Facts()
            val attempts = AtomicInteger()
            val handler = RuntimeTaskHandler { context, _ ->
                attempts.incrementAndGet()
                delay(30)
                facts.commit(context.taskId, context.generation)
            }
            val controller = world.controller(handler, facts)
            val outcomes = (1..8).map {
                async(Dispatchers.IO) { controller.runScheduled(submission) }
            }.awaitAll()
            assertEquals(1, outcomes.map { it.taskId }.toSet().size)
            assertEquals(1, attempts.get())
            val terminal = outcomes.filter { it.state.terminal }
            assertTrue(terminal.isNotEmpty())
            assertTrue(terminal.all { it.state == RuntimeState.COMPLETED })
            assertEquals(
                1,
                world.ledger.events(outcomes.first().taskId, TaskCursor.ZERO)
                    .count { it.type.name == "RECONCILED" },
            )
        }
    }

    private class Facts : BusinessCompletionSource {
        private val facts = ConcurrentHashMap<String, BusinessCompletion>()
        val acknowledged = ConcurrentHashMap.newKeySet<String>()

        fun commit(taskId: TaskId, generation: Long) {
            val key = "${taskId.value}:$generation"
            facts[key] = BusinessCompletion(
                factId = "fact-$key",
                taskId = taskId,
                generation = generation,
                state = RuntimeState.COMPLETED,
                receipt = "receipt-$key",
                committedAt = Instant.parse("2026-11-24T00:00:00Z"),
            )
        }

        override suspend fun find(taskId: TaskId, generation: Long): BusinessCompletion? = facts["${taskId.value}:$generation"]

        override suspend fun pending(consumerId: String, limit: Int): List<BusinessCompletion> = emptyList()

        override suspend fun acknowledge(consumerId: String, factId: String) {
            acknowledged.add(factId)
        }
    }

    private class World(
        val kind: TaskKind,
        val database: SqlDatabase,
        val ledger: SqlTaskLedger,
        val scope: CoroutineScope,
        val clock: WallClock,
        private val directory: java.io.File,
    ) : AutoCloseable {
        private val ids = AtomicInteger()

        fun controller(handler: RuntimeTaskHandler, facts: Facts): RuntimeController {
            val registry = HandlerRegistry(
                mapOf(kind to handler),
                mapOf(kind to facts),
                setOf(kind),
            )
            val supervisor = TaskSupervisor(
                scope,
                ledger,
                LeaseManager(ledger, clock, "s225-worker", Duration.ofSeconds(30)),
                registry,
                clock,
                NoopLogger,
            )
            return RuntimeController(
                ledger,
                supervisor,
                TaskIdSource { TaskId.parse("task-${ids.incrementAndGet()}") },
                clock,
                5,
                registry.resumableKinds,
            )
        }

        suspend fun awaitSnapshot(predicate: (com.deepstudent.services.runtime.api.RuntimeSnapshot) -> Boolean) = withTimeout(5000) {
            var found: com.deepstudent.services.runtime.api.RuntimeSnapshot? = null
            while (found == null) {
                found = ledger.recoverable(null, 10).firstOrNull(predicate)
                if (found == null) delay(5)
            }
            requireNotNull(found)
        }

        override fun close() {
            scope.cancel()
            database.close()
            directory.deleteRecursively()
        }
    }

    private suspend fun withWorld(block: suspend (World) -> Unit) {
        val directory = Files.createTempDirectory("s225-scheduled").toFile()
        val database = DatabaseFactory.openLocal(directory.resolve("runtime.db"))
        val world =
            World(
                kind,
                database,
                SqlTaskLedger(database),
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                WallClock {
                    start
                },
                directory,
            )
        try {
            block(world)
        } finally {
            world.close()
        }
    }
}
