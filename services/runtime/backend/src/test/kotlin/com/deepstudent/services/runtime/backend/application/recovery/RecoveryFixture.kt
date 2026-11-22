package com.deepstudent.services.runtime.backend.application.recovery

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.DeadOwnerPredicate
import com.deepstudent.services.runtime.api.RecoveryDecision
import com.deepstudent.services.runtime.api.RecoveryHandler
import com.deepstudent.services.runtime.api.RecoveryPublisher
import com.deepstudent.services.runtime.api.RecoveryRegistration
import com.deepstudent.services.runtime.api.RuntimeEvent
import com.deepstudent.services.runtime.api.RuntimeExecution
import com.deepstudent.services.runtime.api.RuntimeOwnership
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskHandler
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.api.TaskTimes
import com.deepstudent.services.runtime.backend.HandlerRegistry
import com.deepstudent.services.runtime.backend.LeaseManager
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerCreate
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.RuntimeStateMachine
import com.deepstudent.services.runtime.backend.TaskLedger
import com.deepstudent.services.runtime.backend.TaskSupervisor
import com.deepstudent.services.runtime.backend.leaseToken
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal val fixtureTime: Instant = Instant.parse("2026-11-16T00:00:00Z")
internal val fixtureKind = TaskKind.parse("recovery.fixture")

internal class MemoryLedger : TaskLedger {
    val tasks = linkedMapOf<TaskId, RuntimeSnapshot>()
    val history = mutableListOf<RuntimeEvent>()
    var failReconcile = false
    var cleanupError: Error? = null

    override suspend fun create(taskId: TaskId, submission: TaskSubmission, now: Instant): LedgerCreate {
        val snapshot = RuntimeSnapshot(
            taskId, submission.kind, RuntimeState.CREATED, submission,
            RuntimeOwnership(1, 0, null, null), RuntimeExecution(null, false, null, null, null),
            TaskTimes(now, now), TaskCursor.ZERO,
        )
        tasks[taskId] = snapshot
        return LedgerCreate(snapshot, true)
    }

    override suspend fun snapshot(taskId: TaskId): RuntimeSnapshot? = tasks[taskId]

    override suspend fun events(taskId: TaskId, after: TaskCursor): List<RuntimeEvent> =
        history.filter { it.taskId == taskId && it.cursor.value > after.value }

    override suspend fun recoverable(afterTaskId: String?, limit: Int): List<RuntimeSnapshot> =
        tasks.values.filter { !it.state.terminal && it.taskId.value > (afterTaskId ?: "") }
            .sortedBy { it.taskId.value }.take(limit)

    override suspend fun apply(taskId: TaskId, command: LedgerCommand, now: Instant): LedgerResult {
        if (command is LedgerCommand.Reconcile && failReconcile) error("private SQL error")
        if (command is LedgerCommand.Fence && command.token.ownerId == "recovery") cleanupError?.let { throw it }
        val before = tasks[taskId] ?: return LedgerResult.Missing
        val change = RuntimeStateMachine.decide(before, command, now)
        val applied = change.result as? LedgerResult.Applied ?: return change.result
        val cursor = TaskCursor.parse(history.size.toLong() + 1)
        val snapshot = applied.snapshot.copy(cursor = cursor)
        history.add(RuntimeEvent(cursor, taskId, snapshot.ownership.generation, requireNotNull(change.event), snapshot.state, now))
        tasks[taskId] = snapshot
        return LedgerResult.Applied(snapshot)
    }
}

internal class FakeFacts : BusinessCompletionSource {
    val facts = mutableMapOf<TaskId, BusinessCompletion>()
    val acknowledged = mutableSetOf<String>()
    var failLookup = false
    var failPending = false
    var failAck = false
    var beforeFind: suspend () -> Unit = {}

    override suspend fun find(taskId: TaskId, generation: Long): BusinessCompletion? {
        beforeFind()
        if (failLookup) error("private business text")
        return facts[taskId]?.takeIf { it.generation == generation }
    }

    override suspend fun pending(consumerId: String, limit: Int): List<BusinessCompletion> {
        if (failPending) error("private outbox error")
        return facts.values.filter { it.factId !in acknowledged }.take(limit)
    }

    override suspend fun acknowledge(consumerId: String, factId: String) {
        if (failAck) error("private receipt")
        acknowledged.add(factId)
    }
}

internal class RecoveryFixture : AutoCloseable {
    val ledger = MemoryLedger()
    val facts = FakeFacts()
    val logs = mutableListOf<LogRecord>()
    val published = mutableListOf<RuntimeSnapshot>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val clock = WallClock { fixtureTime.plusSeconds(60) }
    val logger = object : Logger { override fun log(record: LogRecord) { logs.add(record) } }
    val executions = AtomicInteger()
    val handler = RuntimeTaskHandler { _, _ -> executions.incrementAndGet(); Unit }
    val supervisor = TaskSupervisor(
        scope, ledger, LeaseManager(ledger, clock, "recovery", Duration.ofMinutes(2)),
        HandlerRegistry(mapOf(fixtureKind to handler), mapOf(fixtureKind to facts)), clock, logger,
    )

    fun coordinator(
        decision: RecoveryDecision = RecoveryDecision.Interrupted,
        dead: Boolean = true,
    ): RecoveryCoordinator = RecoveryCoordinator(
        RecoveryActions(ledger, supervisor, clock, "recovery", Duration.ofMinutes(2), DeadOwnerPredicate { dead }),
        listOf(RecoveryRegistration(fixtureKind, facts, RecoveryHandler { decision })),
        RecoveryPublisher { published.add(it) }, logger, MonotonicClock { 1000000L }, 2,
    )

    suspend fun running(id: String, checkpoint: String = "partial"): RuntimeSnapshot {
        val task = TaskId.parse(id)
        val submission = TaskSubmission(fixtureKind, JsonValue.text("secret payload"), CorrelationId.parse("corr-$id"))
        ledger.create(task, submission, fixtureTime)
        val claim = ledger.apply(task, LedgerCommand.Claim("old", fixtureTime.plusSeconds(20)), fixtureTime) as LedgerResult.Applied
        val token = requireNotNull(claim.snapshot.leaseToken())
        ledger.apply(task, LedgerCommand.Transition(token, RuntimeState.RUNNING), fixtureTime)
        ledger.apply(task, LedgerCommand.Checkpoint(token, JsonValue.text(checkpoint)), fixtureTime)
        return requireNotNull(ledger.snapshot(task))
    }

    fun complete(id: String): BusinessCompletion = BusinessCompletion(
        "fact-$id", TaskId.parse(id), 1, RuntimeState.COMPLETED, "receipt-$id", fixtureTime,
    ).also { facts.facts[it.taskId] = it }

    override fun close() { scope.cancel() }
}
