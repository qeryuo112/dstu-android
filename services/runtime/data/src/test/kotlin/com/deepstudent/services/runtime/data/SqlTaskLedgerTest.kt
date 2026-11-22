package com.deepstudent.services.runtime.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.BusinessCompletionSource
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class SqlTaskLedgerTest {
    private val now = Instant.parse("2026-11-16T00:00:00Z")
    private val kind = TaskKind.parse("test.task")

    @Test
    fun dedupeReplayFencingAndBusinessFactSurviveReopen() = runBlocking {
        val dir = Files.createTempDirectory("runtime-ledger").toFile()
        val file = dir.resolve("runtime.db")
        val task = TaskId.parse("task-1")
        val submission = TaskSubmission(kind, JsonValue.text("payload"), dedupeKey = "once")
        try {
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                assertTrue(ledger.create(task, submission, now).inserted)
                assertFalse(ledger.create(TaskId.parse("task-2"), submission, now).inserted)
                val first = (ledger.apply(task, LedgerCommand.Claim("old", now.plusSeconds(10)), now) as LedgerResult.Applied).snapshot
                val oldToken = requireNotNull(first.leaseToken())
                ledger.apply(task, LedgerCommand.Transition(oldToken, RuntimeState.RUNNING), now)
                ledger.apply(task, LedgerCommand.Checkpoint(oldToken, JsonValue.text("checkpoint")), now)
                ledger.apply(task, LedgerCommand.Fence(oldToken), now)
                val second = (ledger.apply(task, LedgerCommand.Claim("new", now.plusSeconds(20)), now) as LedgerResult.Applied).snapshot
                val stale = ledger.apply(task, LedgerCommand.Checkpoint(oldToken, JsonValue.text("stale")), now)
                assertEquals(LedgerRejection.STALE_OWNER, (stale as LedgerResult.Rejected).reason)
                assertTrue(second.ownership.ownerEpoch > first.ownership.ownerEpoch)
                val fact = BusinessCompletion("fact-1", task, 1, RuntimeState.COMPLETED, "receipt-1", now)
                val staleFact = ledger.apply(task, LedgerCommand.Reconcile(fact, oldToken), now)
                assertEquals(LedgerRejection.STALE_OWNER, (staleFact as LedgerResult.Rejected).reason)
                assertTrue(ledger.apply(task, LedgerCommand.Reconcile(fact, requireNotNull(second.leaseToken())), now) is LedgerResult.Applied)
                assertTrue(ledger.apply(task, LedgerCommand.Reconcile(fact), now) is LedgerResult.Unchanged)
            }
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                val snapshot = requireNotNull(ledger.snapshot(task))
                assertEquals(RuntimeState.COMPLETED, snapshot.state)
                assertEquals("receipt-1", snapshot.execution.receipt)
                assertEquals(JsonValue.text("checkpoint"), snapshot.execution.checkpoint)
                val events = ledger.events(task, TaskCursor.ZERO)
                assertEquals(snapshot.cursor, events.last().cursor)
                assertEquals(events.drop(2), ledger.events(task, events[1].cursor))
                assertTrue(ledger.recoverable(null, 100).isEmpty())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun concurrentConnectionsDeduplicateAndDifferentPayloadIsRejected() = runBlocking {
        val dir = Files.createTempDirectory("runtime-dedupe").toFile()
        try {
            val file = dir.resolve("runtime.db")
            DatabaseFactory.openLocal(file).use { first ->
                DatabaseFactory.openLocal(file).use { second ->
                    val ledgers = listOf(SqlTaskLedger(first), SqlTaskLedger(second))
                    val submission = TaskSubmission(kind, JsonValue.text("same"), dedupeKey = "key")
                    val results = (1..20).map { index ->
                        async(Dispatchers.IO) {
                            ledgers[index % 2].create(TaskId.parse("task-$index"), submission, now)
                        }
                    }.awaitAll()
                    assertEquals(1, results.count { it.inserted })
                    assertEquals(1, results.map { it.snapshot.taskId }.toSet().size)
                    val failure = runCatching {
                        ledgers.first().create(TaskId.parse("conflict"), submission.copy(payload = JsonValue.text("different")), now)
                    }.exceptionOrNull()
                    assertTrue(failure is IllegalStateException)
                    assertEquals(1, ledgers.first().recoverable(null, 10).size)
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unknownLocalSchemaVersionFailsClosed() {
        val dir = Files.createTempDirectory("runtime-version").toFile()
        try {
            DatabaseFactory.openLocal(dir.resolve("runtime.db")).use { database ->
                database.execute("CREATE TABLE runtime_schema (version INTEGER NOT NULL)")
                database.execute("INSERT INTO runtime_schema(version) VALUES (3)")
                assertTrue(runCatching { SqlTaskLedger(database) }.exceptionOrNull() is IllegalStateException)
                assertEquals(3L, database.query("SELECT version FROM runtime_schema") { it.long(0) }.single())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun versionOneMigratesWithoutLosingEventsAndConfirmationSurvivesReopen() = runBlocking {
        val dir = Files.createTempDirectory("runtime-schema-upgrade").toFile()
        val file = dir.resolve("runtime.db")
        val task = TaskId.parse("upgrade")
        try {
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                ledger.create(task, TaskSubmission(kind), now)
                val claim = ledger.apply(task, LedgerCommand.Claim("owner", now.plusSeconds(30)), now) as LedgerResult.Applied
                val token = requireNotNull(claim.snapshot.leaseToken())
                ledger.apply(task, LedgerCommand.Transition(token, RuntimeState.RUNNING), now)
                ledger.apply(task, LedgerCommand.Checkpoint(token, JsonValue.text("partial")), now)
                database.execute("ALTER TABLE runtime_tasks DROP COLUMN confirmation_required")
                database.execute("UPDATE runtime_schema SET version = 1")
            }
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                assertEquals(2L, database.query("SELECT version FROM runtime_schema") { it.long(0) }.single())
                val before = requireNotNull(ledger.snapshot(task))
                assertFalse(before.execution.confirmationRequired)
                assertEquals(JsonValue.text("partial"), before.execution.checkpoint)
                val token = requireNotNull(before.leaseToken())
                val result = ledger.apply(task, LedgerCommand.RequireConfirmation(token), now) as LedgerResult.Applied
                assertEquals(RuntimeState.PAUSED, result.snapshot.state)
            }
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                val snapshot = requireNotNull(ledger.snapshot(task))
                assertTrue(snapshot.execution.confirmationRequired)
                assertEquals(JsonValue.text("partial"), snapshot.execution.checkpoint)
                assertEquals("CONFIRMATION_REQUIRED", ledger.events(task, TaskCursor.ZERO).last().type.name)
                val rejected = ledger.apply(task, LedgerCommand.Claim("worker", now.plusSeconds(30)), now) as LedgerResult.Rejected
                assertEquals(LedgerRejection.CONFIRMATION_REQUIRED, rejected.reason)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parallelCompletionFailureCancellationAndUiCloseAreIndependent() = runBlocking {
        val dir = Files.createTempDirectory("runtime-supervisor").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val logs = Collections.synchronizedList(mutableListOf<LogRecord>())
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val failure = "secret-token-and-private-path"
        try {
            DatabaseFactory.openLocal(dir.resolve("runtime.db")).use { database ->
                val ledger = SqlTaskLedger(database)
                val clock = WallClock { now }
                val handler = RuntimeTaskHandler { context, payload ->
                    when ((payload as JsonValue.Text).value) {
                        "complete" -> { entered.complete(Unit); finish.await(); context.checkpoint(JsonValue.text("saved")) }
                        "fail" -> throw IllegalStateException(failure)
                        "cancel" -> CompletableDeferred<Unit>().await()
                    }
                }
                val logger = object : Logger { override fun log(record: LogRecord) { logs.add(record) } }
                val supervisor = TaskSupervisor(scope, ledger, LeaseManager(ledger, clock, "app", Duration.ofMinutes(10)), HandlerRegistry(mapOf(kind to handler)), clock, logger)
                val ids = AtomicInteger()
                val controller = RuntimeController(ledger, supervisor, TaskIdSource { TaskId.parse("task-${ids.incrementAndGet()}") }, clock, 5)
                val completed = controller.submit(TaskSubmission(kind, JsonValue.text("complete"), dedupeKey = "same"))
                entered.await()
                val observer = launch { controller.observe(completed.taskId, null).first() }
                observer.join()
                val duplicate = controller.submit(TaskSubmission(kind, JsonValue.text("complete"), dedupeKey = "same"))
                assertTrue(duplicate.deduplicated)
                assertEquals(completed.taskId, duplicate.taskId)
                val failed = controller.submit(TaskSubmission(kind, JsonValue.text("fail")))
                val cancelled = controller.submit(TaskSubmission(kind, JsonValue.text("cancel")))
                controller.cancel(cancelled.taskId)
                finish.complete(Unit)
                awaitState(controller, completed.taskId, RuntimeState.COMPLETED)
                awaitState(controller, failed.taskId, RuntimeState.FAILED)
                awaitState(controller, cancelled.taskId, RuntimeState.CANCELLED)
                assertEquals(JsonValue.text("saved"), controller.snapshot(completed.taskId)?.execution?.checkpoint)
                assertNotNull(controller.snapshot(failed.taskId)?.execution?.error)
                assertFalse(controller.snapshot(failed.taskId).toString().contains(failure))
                assertFalse(logs.toString().contains(failure))
                assertTrue(controller.activeSnapshots(null, 100).isEmpty())
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun leaseRenewsWithoutHandlerHelpAndThrowingLoggerCannotChangeCompletion() = runBlocking {
        val dir = Files.createTempDirectory("runtime-renew").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            DatabaseFactory.openLocal(dir.resolve("runtime.db")).use { database ->
                val ledger = SqlTaskLedger(database)
                val clock = WallClock { Instant.now() }
                val handler = RuntimeTaskHandler { _, _ -> delay(350) }
                val logger = object : Logger { override fun log(record: LogRecord) { error("private logger error") } }
                val supervisor = TaskSupervisor(scope, ledger, LeaseManager(ledger, clock, "app", Duration.ofMillis(150)), HandlerRegistry(mapOf(kind to handler)), clock, logger)
                val controller = RuntimeController(ledger, supervisor, TaskIdSource { TaskId.parse("renew") }, clock, 5)
                val task = controller.submit(TaskSubmission(kind))
                awaitState(controller, task.taskId, RuntimeState.COMPLETED)
                assertTrue(ledger.events(task.taskId, TaskCursor.ZERO).count { it.type.name == "LEASE_CHANGED" } >= 2)
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun registeredBusinessSourceControlsCompletionProjection() = runBlocking {
        val dir = Files.createTempDirectory("runtime-fact").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            DatabaseFactory.openLocal(dir.resolve("runtime.db")).use { database ->
                val ledger = SqlTaskLedger(database)
                val clock = WallClock { now }
                val acknowledged = CompletableDeferred<String>()
                val source = object : BusinessCompletionSource {
                    override suspend fun find(taskId: TaskId, generation: Long): BusinessCompletion? =
                        if (taskId.value == "with-fact") BusinessCompletion("fact-1", taskId, generation, RuntimeState.COMPLETED, "receipt", now) else null
                    override suspend fun pending(consumerId: String, limit: Int): List<BusinessCompletion> = emptyList()
                    override suspend fun acknowledge(consumerId: String, factId: String) {
                        assertEquals("runtime.recovery", consumerId)
                        assertEquals("receipt", ledger.snapshot(TaskId.parse("with-fact"))?.execution?.receipt)
                        acknowledged.complete(factId)
                    }
                }
                val completed = CompletableDeferred<Unit>()
                val handler = RuntimeTaskHandler { _, _ -> completed.complete(Unit) }
                val registry = HandlerRegistry(mapOf(kind to handler), mapOf(kind to source))
                val logger = object : Logger { override fun log(record: LogRecord) = Unit }
                val supervisor = TaskSupervisor(scope, ledger, LeaseManager(ledger, clock, "app", Duration.ofSeconds(30)), registry, clock, logger)
                val first = RuntimeController(ledger, supervisor, TaskIdSource { TaskId.parse("with-fact") }, clock, 5)
                val task = first.submit(TaskSubmission(kind))
                awaitState(first, task.taskId, RuntimeState.COMPLETED)
                assertEquals("receipt", first.snapshot(task.taskId)?.execution?.receipt)
                assertEquals("fact-1", withTimeout(5000) { acknowledged.await() })
                val second = RuntimeController(ledger, supervisor, TaskIdSource { TaskId.parse("missing-fact") }, clock, 5)
                val pending = second.submit(TaskSubmission(kind))
                withTimeout(5000) {
                    while (second.snapshot(pending.taskId)?.state != RuntimeState.RUNNING) delay(5)
                }
                delay(50)
                assertEquals(RuntimeState.RUNNING, second.snapshot(pending.taskId)?.state)
                assertEquals(null, second.snapshot(pending.taskId)?.execution?.receipt)
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancellationWithoutBusinessFactRemainsRecoverable() = runBlocking {
        val dir = Files.createTempDirectory("runtime-cancel-fact").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val finishing = CompletableDeferred<Unit>()
        try {
            DatabaseFactory.openLocal(dir.resolve("runtime.db")).use { database ->
                val ledger = SqlTaskLedger(database)
                val clock = WallClock { now }
                val source = object : BusinessCompletionSource {
                    override suspend fun find(taskId: TaskId, generation: Long): BusinessCompletion? {
                        finishing.complete(Unit)
                        return null
                    }
                    override suspend fun pending(consumerId: String, limit: Int): List<BusinessCompletion> = emptyList()
                    override suspend fun acknowledge(consumerId: String, factId: String) = Unit
                }
                val handler = RuntimeTaskHandler { _, _ -> entered.complete(Unit); CompletableDeferred<Unit>().await() }
                val logger = object : Logger { override fun log(record: LogRecord) = Unit }
                val registry = HandlerRegistry(mapOf(kind to handler), mapOf(kind to source))
                val supervisor = TaskSupervisor(scope, ledger, LeaseManager(ledger, clock, "app", Duration.ofSeconds(30)), registry, clock, logger)
                val controller = RuntimeController(ledger, supervisor, TaskIdSource { TaskId.parse("cancel-fact") }, clock, 5)
                val task = controller.submit(TaskSubmission(kind))
                entered.await()
                controller.cancel(task.taskId)
                withTimeout(5000) { finishing.await() }
                delay(50)
                val snapshot = requireNotNull(controller.snapshot(task.taskId))
                assertTrue(snapshot.execution.cancelRequested)
                assertFalse(snapshot.state.terminal)
                assertEquals(task.taskId, controller.activeSnapshots(null, 10).single().taskId)
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun recoveryCanTransferClaimToSupervisorWithoutReclaiming() = runBlocking {
        val dir = Files.createTempDirectory("runtime-transfer").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            DatabaseFactory.openLocal(dir.resolve("runtime.db")).use { database ->
                val ledger = SqlTaskLedger(database)
                val clock = WallClock { now }
                val taskId = TaskId.parse("resume")
                ledger.create(taskId, TaskSubmission(kind), now)
                val claim = ledger.apply(taskId, LedgerCommand.Claim("recovery", now.plusSeconds(30)), now) as LedgerResult.Applied
                val token = requireNotNull(claim.snapshot.leaseToken())
                val logger = object : Logger { override fun log(record: LogRecord) = Unit }
                val supervisor = TaskSupervisor(scope, ledger, LeaseManager(ledger, clock, "app", Duration.ofSeconds(30)), HandlerRegistry(mapOf(kind to RuntimeTaskHandler { _, _ -> })), clock, logger)
                val controller = RuntimeController(ledger, supervisor, TaskIdSource { TaskId.parse("unused") }, clock, 5)
                assertTrue(supervisor.startClaimed(claim.snapshot, token))
                awaitState(controller, taskId, RuntimeState.COMPLETED)
                assertEquals(token.ownerEpoch, controller.snapshot(taskId)?.ownership?.ownerEpoch)
                assertFalse(supervisor.startClaimed(requireNotNull(controller.snapshot(taskId)), token))
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    private suspend fun awaitState(controller: RuntimeController, taskId: TaskId, state: RuntimeState) {
        withTimeout(5000) {
            while (controller.snapshot(taskId)?.state != state) delay(5)
        }
    }
}
