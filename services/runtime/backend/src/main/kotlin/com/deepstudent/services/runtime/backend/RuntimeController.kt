package com.deepstudent.services.runtime.backend

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.services.runtime.api.RuntimeCancelResult
import com.deepstudent.services.runtime.api.RuntimeEvent
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeSnapshotObserver
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskCanceller
import com.deepstudent.services.runtime.api.RuntimeTaskCatalog
import com.deepstudent.services.runtime.api.RuntimeTaskObserver
import com.deepstudent.services.runtime.api.RuntimeTaskQuery
import com.deepstudent.services.runtime.api.ScheduledRunOutcome
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskHandle
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.api.TaskSubmitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

fun interface TaskIdSource {
    fun next(): TaskId
}

/** Thin facade: ledger and application-owned supervisor do the work. */
class RuntimeController(
    private val ledger: TaskLedger,
    private val supervisor: TaskSupervisor,
    private val ids: TaskIdSource,
    private val clock: WallClock,
    private val pollMillis: Long = 100,
    private val resumableKinds: Set<TaskKind> = emptySet(),
) : TaskSubmitter,
    RuntimeTaskCanceller,
    RuntimeTaskQuery,
    RuntimeTaskObserver,
    RuntimeSnapshotObserver,
    RuntimeTaskCatalog {
    init {
        require(pollMillis > 0) { "poll interval must be positive" }
    }

    override suspend fun submit(submission: TaskSubmission): TaskHandle {
        val created = ledger.create(ids.next(), submission, clock.now())
        if (created.inserted) supervisor.start(created.snapshot)
        return TaskHandle(created.snapshot.taskId, deduplicated = !created.inserted)
    }

    /**
     * Runs a dedupe-keyed scheduled task and waits for its durable settlement.
     *
     * The same key always maps to the same task id and generation: an existing task is reused,
     * never replaced by a new id. Only explicitly resumable kinds are accepted. When another
     * owner already holds the task, or the task is gated, the call returns immediately with a
     * non-terminal [ScheduledRunOutcome] so the caller retries later instead of waiting forever.
     *
     * Cancelling the caller's coroutine yields only the execution this call started: the handler
     * is cancelled, committed business facts are reconciled first, an already durable user
     * cancellation is settled, and otherwise the lease is released non-terminally with its
     * checkpoint preserved. Another owner's execution is never stopped.
     */
    suspend fun runScheduled(submission: TaskSubmission): ScheduledRunOutcome = runPreparedScheduled(prepareScheduled(submission))

    /** Persists a task identity before launching, so the schedule owner can serialize admission and cancellation. */
    suspend fun prepareScheduled(submission: TaskSubmission): TaskHandle {
        require(submission.dedupeKey != null) { "scheduled task requires a stable dedupe key" }
        require(submission.kind in resumableKinds) { "task kind is not registered as resumable" }
        val created = ledger.create(ids.next(), submission, clock.now())
        return TaskHandle(created.snapshot.taskId, deduplicated = !created.inserted)
    }

    suspend fun runPreparedScheduled(handle: TaskHandle): ScheduledRunOutcome {
        require(ledger.snapshot(handle.taskId)?.kind in resumableKinds) { "not a scheduled task" }
        return followScheduled(handle.taskId, reused = handle.deduplicated)
    }

    private suspend fun followScheduled(taskId: TaskId, reused: Boolean): ScheduledRunOutcome {
        val snapshot = scheduledSnapshot(taskId)
        if (!snapshot.canStartScheduled()) return snapshot.scheduledOutcome(reused, executed = false)
        val started = supervisor.startOwned(snapshot) ?: return scheduledSnapshot(taskId).scheduledOutcome(reused, executed = false)
        try {
            started.awaitSettlement()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { supervisor.yieldAndAwait(started) }
            throw cancelled
        }
        return scheduledSnapshot(taskId).scheduledOutcome(reused, executed = true)
    }

    private suspend fun scheduledSnapshot(taskId: TaskId): RuntimeSnapshot {
        val snapshot = checkNotNull(ledger.snapshot(taskId)) { "scheduled task disappeared" }
        if (!snapshot.state.terminal && snapshot.execution.cancelRequested && snapshot.ownership.ownerId == null) {
            ledger.apply(taskId, LedgerCommand.SettleCancelled, clock.now())
            return checkNotNull(ledger.snapshot(taskId))
        }
        return snapshot
    }

    private fun RuntimeSnapshot.canStartScheduled(): Boolean =
        !state.terminal && !execution.cancelRequested && ownership.ownerId == null && !gated()

    private fun RuntimeSnapshot.scheduledOutcome(reused: Boolean, executed: Boolean): ScheduledRunOutcome =
        ScheduledRunOutcome(taskId, ownership.generation, state, reused, executed)

    private fun RuntimeSnapshot.gated(): Boolean =
        execution.confirmationRequired || state == RuntimeState.PAUSED || state == RuntimeState.WAITING_APPROVAL

    override suspend fun cancel(taskId: TaskId): RuntimeCancelResult = requestCancel(taskId, LedgerCommand.RequestCancel)

    override suspend fun cancelIfGeneration(taskId: TaskId, generation: Long): RuntimeCancelResult {
        require(generation > 0) { "invalid task generation" }
        return requestCancel(taskId, LedgerCommand.RequestCancelGeneration(generation))
    }

    private suspend fun requestCancel(taskId: TaskId, command: LedgerCommand): RuntimeCancelResult {
        val result = ledger.apply(taskId, command, clock.now())
        return when (result) {
            is LedgerResult.Applied, is LedgerResult.Unchanged -> {
                supervisor.cancel(taskId)
                val latest = ledger.snapshot(taskId)
                if (latest != null && latest.kind in resumableKinds && latest.ownership.ownerId == null && !latest.state.terminal) {
                    ledger.apply(taskId, LedgerCommand.SettleCancelled, clock.now())
                }
                RuntimeCancelResult.Requested(taskId)
            }

            is LedgerResult.Rejected -> if (result.reason == LedgerRejection.STALE_OWNER) {
                RuntimeCancelResult.StaleGeneration(taskId)
            } else {
                RuntimeCancelResult.AlreadyTerminal(taskId, result.snapshot.state)
            }

            LedgerResult.Missing -> RuntimeCancelResult.NotFound(taskId)
        }
    }

    override suspend fun snapshot(taskId: TaskId): RuntimeSnapshot? = ledger.snapshot(taskId)

    override suspend fun events(taskId: TaskId, after: TaskCursor): List<RuntimeEvent> = ledger.events(taskId, after)

    override suspend fun activeSnapshots(afterTaskId: String?, limit: Int): List<RuntimeSnapshot> = ledger.recoverable(afterTaskId, limit)

    override fun observe(taskId: TaskId, after: TaskCursor?): Flow<RuntimeEvent> = flow {
        var cursor = after ?: TaskCursor.ZERO
        while (currentCoroutineContext().isActive) {
            for (event in ledger.events(taskId, cursor)) {
                emit(event)
                cursor = event.cursor
            }
            delay(pollMillis)
        }
    }

    override fun observeSnapshots(taskId: TaskId): Flow<RuntimeSnapshot?> = flow {
        while (currentCoroutineContext().isActive) {
            emit(ledger.snapshot(taskId))
            delay(pollMillis)
        }
    }.distinctUntilChanged()
}
