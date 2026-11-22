package com.deepstudent.services.runtime.backend

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.services.runtime.api.RuntimeCancelResult
import com.deepstudent.services.runtime.api.RuntimeEvent
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeSnapshotObserver
import com.deepstudent.services.runtime.api.RuntimeTaskCanceller
import com.deepstudent.services.runtime.api.RuntimeTaskCatalog
import com.deepstudent.services.runtime.api.RuntimeTaskObserver
import com.deepstudent.services.runtime.api.RuntimeTaskQuery
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskHandle
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.api.TaskSubmitter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

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
) : TaskSubmitter, RuntimeTaskCanceller, RuntimeTaskQuery, RuntimeTaskObserver, RuntimeSnapshotObserver,
    RuntimeTaskCatalog {
    init {
        require(pollMillis > 0) { "poll interval must be positive" }
    }

    override suspend fun submit(submission: TaskSubmission): TaskHandle {
        val created = ledger.create(ids.next(), submission, clock.now())
        if (created.inserted) supervisor.start(created.snapshot)
        return TaskHandle(created.snapshot.taskId, deduplicated = !created.inserted)
    }

    override suspend fun cancel(taskId: TaskId): RuntimeCancelResult {
        return requestCancel(taskId, LedgerCommand.RequestCancel)
    }

    override suspend fun cancelIfGeneration(taskId: TaskId, generation: Long): RuntimeCancelResult {
        require(generation > 0) { "invalid task generation" }
        return requestCancel(taskId, LedgerCommand.RequestCancelGeneration(generation))
    }

    private suspend fun requestCancel(taskId: TaskId, command: LedgerCommand): RuntimeCancelResult {
        val result = ledger.apply(taskId, command, clock.now())
        return when (result) {
            is LedgerResult.Applied, is LedgerResult.Unchanged -> {
                supervisor.cancel(taskId)
                RuntimeCancelResult.Requested(taskId)
            }
            is LedgerResult.Rejected -> if (result.reason == LedgerRejection.STALE_OWNER) {
                RuntimeCancelResult.StaleGeneration(taskId)
            } else RuntimeCancelResult.AlreadyTerminal(taskId, result.snapshot.state)
            LedgerResult.Missing -> RuntimeCancelResult.NotFound(taskId)
        }
    }

    override suspend fun snapshot(taskId: TaskId): RuntimeSnapshot? = ledger.snapshot(taskId)

    override suspend fun events(taskId: TaskId, after: TaskCursor): List<RuntimeEvent> = ledger.events(taskId, after)

    override suspend fun activeSnapshots(afterTaskId: String?, limit: Int): List<RuntimeSnapshot> =
        ledger.recoverable(afterTaskId, limit)

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
