package com.deepstudent.services.runtime.backend

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.ClockChecks
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.core.common.time.SystemMonotonicClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Owned by the application scope. Each task has its own supervisor and cancellation. */
class TaskSupervisor(
    private val scope: CoroutineScope,
    private val ledger: TaskLedger,
    private val leases: LeaseManager,
    private val handlers: HandlerRegistry,
    private val clock: WallClock,
    private val logger: Logger,
    private val monotonicClock: MonotonicClock = SystemMonotonicClock,
) {
    private val jobs = ConcurrentHashMap<TaskId, TaskSlot>()
    private val contexts = ConcurrentHashMap<TaskId, TaskContext>()
    private val completion = TaskCompletion(ledger, handlers, clock)

    suspend fun start(snapshot: RuntimeSnapshot): Boolean = startOwned(snapshot) != null

    /**
     * Starts one execution and returns a handle owned by this caller. Returns null when another
     * execution already holds the task, when no lease could be claimed, or when the task is
     * terminal/gated. The handle settles only after the execution's durable cleanup completed.
     */
    suspend fun startOwned(snapshot: RuntimeSnapshot): TaskExecution? {
        if (snapshot.state.terminal || snapshot.execution.confirmationRequired) return null
        val slot = TaskSlot()
        if (jobs.putIfAbsent(snapshot.taskId, slot) != null) return null
        val token = try {
            leases.claim(snapshot.taskId)
        } catch (failure: Throwable) {
            jobs.remove(snapshot.taskId, slot)
            throw failure
        }
        if (token == null) {
            jobs.remove(snapshot.taskId, slot)
            return null
        }
        return launch(snapshot, token, slot)
    }

    /** Transfers an existing recovery claim into the application-owned supervisor. */
    suspend fun startClaimed(snapshot: RuntimeSnapshot, token: LeaseToken): Boolean {
        if (token.recoveryOnly) return false
        if (snapshot.state.terminal || snapshot.execution.confirmationRequired || snapshot.leaseToken() != token) return false
        val slot = TaskSlot()
        if (jobs.putIfAbsent(snapshot.taskId, slot) != null) return false
        try {
            leases.renew(token)
        } catch (failure: Throwable) {
            jobs.remove(snapshot.taskId, slot)
            throw failure
        }
        launch(snapshot, token, slot)
        return true
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun launch(snapshot: RuntimeSnapshot, token: LeaseToken, slot: TaskSlot): TaskExecution {
        val taskJob = SupervisorJob(scope.coroutineContext[Job])
        val child = scope.launch(taskJob, start = CoroutineStart.ATOMIC) {
            execute(snapshot, token, slot)
        }
        slot.install(taskJob, token.ownerEpoch)
        child.invokeOnCompletion { taskJob.complete() }
        taskJob.invokeOnCompletion {
            jobs.remove(snapshot.taskId, slot)
            slot.completion.complete(Unit)
        }
        return TaskExecution(snapshot.taskId, token.ownerEpoch, slot.completion)
    }

    suspend fun cancel(taskId: TaskId) {
        jobs[taskId]?.cancel()
    }

    /**
     * Stops only the execution identified by [execution] and waits for its durable cleanup.
     * A different owner's execution, or an already finished one, is left untouched.
     */
    suspend fun yieldAndAwait(execution: TaskExecution) {
        val slot = jobs[execution.taskId] ?: return
        if (!slot.yield(execution.ownerEpoch)) return
        execution.awaitSettlement()
    }

    /** Returns only after the active execution owner's checkpoint is durably re-acknowledged. */
    suspend fun requestCheckpoint(taskId: TaskId, generation: Long) {
        val context = contexts[taskId] ?: throw LeaseLostException()
        require(context.generation == generation) { "stale checkpoint generation" }
        context.flushCheckpoint()
    }

    private suspend fun execute(snapshot: RuntimeSnapshot, token: LeaseToken, slot: TaskSlot) {
        val started = monotonicClock.nowNanos()
        var terminal: RuntimeState? = RuntimeState.INTERRUPTED
        var error: DstuError? = null
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val current = ledger.snapshot(snapshot.taskId) ?: throw LeaseLostException()
            val context = TaskContext(ledger, leases, clock, token, current.execution.checkpoint)
            contexts[snapshot.taskId] = context
            if (current.state != RuntimeState.RUNNING) context.transition(RuntimeState.RUNNING)
            val handler = handlers.find(current.kind) ?: throw MissingHandlerException()
            executeWithRenewal(context, current, handler)
            terminal = RuntimeState.COMPLETED
        } catch (failure: CancellationException) {
            terminal = if (slot.yieldRequested) null else RuntimeState.INTERRUPTED
        } catch (failure: Exception) {
            terminal = RuntimeState.FAILED
            error = DstuError.internal("TASK_FAILED", "task execution failed")
        } finally {
            contexts.remove(snapshot.taskId)
            withContext(NonCancellable) { complete(snapshot, token, terminal, error, started) }
        }
    }

    private suspend fun executeWithRenewal(
        context: TaskContext,
        snapshot: RuntimeSnapshot,
        handler: com.deepstudent.services.runtime.api.RuntimeTaskHandler,
    ) = coroutineScope {
        val renewal = launch {
            while (true) {
                delay(leases.renewalIntervalMillis)
                context.renewLease()
            }
        }
        try {
            handler.execute(context, snapshot.submission.payload)
        } finally {
            renewal.cancelAndJoin()
        }
    }

    private suspend fun complete(snapshot: RuntimeSnapshot, token: LeaseToken, state: RuntimeState?, error: DstuError?, started: Long) {
        try {
            val outcome = if (state == null) {
                completion.yield(snapshot, token)
            } else {
                completion.finish(snapshot, token, state, error)
            }
            log(snapshot, outcome.state, outcome.event, started, if (outcome.state == RuntimeState.FAILED) error else null)
        } catch (failure: Exception) {
            log(snapshot, null, "runtime.finish_unavailable", started)
        }
    }

    private fun log(snapshot: RuntimeSnapshot, state: RuntimeState?, event: String, started: Long, error: DstuError? = null) {
        val fields = JsonValue.Object.Builder().put("owner", "runtime").apply {
            if (state != null) put("state", state.name.lowercase())
            if (event == "runtime.finished") put("stage", "COMPLETE")
            ClockChecks.monotonicElapsedMillis(started, monotonicClock.nowNanos())?.let { put("duration_ms", it) }
        }.build()
        try {
            val level = if (error == null) LogLevel.INFO else LogLevel.ERROR
            logger.log(LogRecord(level, event, snapshot.taskId, snapshot.correlationId, fields, error))
        } catch (_: Exception) {
            // Diagnostics must not change task state.
        }
    }
}

private class MissingHandlerException : IllegalStateException("handler not registered")

/** Cooperative cancellation raised only by [TaskSupervisor.yieldAndAwait]. */
private class TaskYieldCancellation : CancellationException("scheduled execution yielded")

/** Handle for one execution. Settlement follows the durable finish/release and fact projection. */
class TaskExecution internal constructor(val taskId: TaskId, val ownerEpoch: Long, internal val completion: CompletableDeferred<Unit>) {
    suspend fun awaitSettlement() = completion.await()
}

private class TaskSlot {
    val completion = CompletableDeferred<Unit>()
    private var job: Job? = null
    private var cancelled = false
    private var yielded = false
    private var ownerEpoch = 0L

    @Synchronized
    fun install(value: Job, executionEpoch: Long) {
        ownerEpoch = executionEpoch
        job = value
        when {
            cancelled -> value.cancel(CancellationException("task cancellation requested"))
            yielded -> value.cancel(TaskYieldCancellation())
        }
    }

    @Synchronized
    fun cancel() {
        cancelled = true
        job?.cancel(CancellationException("task cancellation requested"))
    }

    /** Requests a yield only when [executionEpoch] still matches this slot's execution. */
    @Synchronized
    fun yield(executionEpoch: Long): Boolean {
        if (yielded || cancelled || ownerEpoch != executionEpoch) return false
        yielded = true
        job?.cancel(TaskYieldCancellation())
        return true
    }

    val yieldRequested: Boolean
        @Synchronized get() = yielded
}

private class TaskContext(
    private val ledger: TaskLedger,
    private val leases: LeaseManager,
    private val clock: WallClock,
    private val token: LeaseToken,
    override val resumeCheckpoint: JsonValue?,
) : RuntimeTaskContext {
    private val checkpointLock = Mutex()
    private var checkpointValue: JsonValue? = null
    override val taskId: TaskId get() = token.taskId
    override val generation: Long get() = token.generation

    override suspend fun checkpoint(value: JsonValue?) {
        checkpointLock.withLock {
            ledger.apply(taskId, LedgerCommand.Checkpoint(token, value), clock.now()).requireApplied()
            checkpointValue = value
        }
    }

    suspend fun flushCheckpoint() = checkpointLock.withLock {
        val value = checkpointValue ?: ledger.snapshot(taskId)?.execution?.checkpoint
        ledger.apply(taskId, LedgerCommand.Checkpoint(token, value), clock.now()).requireApplied()
    }

    override suspend fun transition(state: RuntimeState) {
        ledger.apply(taskId, LedgerCommand.Transition(token, state), clock.now()).requireApplied()
    }

    override suspend fun renewLease() {
        leases.renew(token)
    }
}
