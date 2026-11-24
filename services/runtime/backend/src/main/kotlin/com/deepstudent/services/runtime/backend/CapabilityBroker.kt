package com.deepstudent.services.runtime.backend

import com.deepstudent.core.common.time.WallClock
import com.deepstudent.services.runtime.api.CapabilityAddress
import com.deepstudent.services.runtime.api.CapabilityApprovals
import com.deepstudent.services.runtime.api.CapabilityBinding
import com.deepstudent.services.runtime.api.CapabilityBlock
import com.deepstudent.services.runtime.api.CapabilityEpoch
import com.deepstudent.services.runtime.api.CapabilityEpochStore
import com.deepstudent.services.runtime.api.CapabilityOutcome
import com.deepstudent.services.runtime.api.CapabilityTicket
import com.deepstudent.services.runtime.api.CapabilityTicketReader
import com.deepstudent.services.runtime.api.RuntimeApprovalState
import com.deepstudent.services.runtime.api.RuntimeProjectionChange
import com.deepstudent.services.runtime.api.RuntimeProjectionResult
import com.deepstudent.services.runtime.api.RuntimeProjectionStore
import com.deepstudent.services.runtime.api.RuntimeRecordKey
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import com.deepstudent.services.runtime.api.RuntimeTaskGeneration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** One-call approval. The persisted projection is the UI source, never an executable token. */
class CapabilityBroker(
    private val projections: RuntimeProjectionStore,
    private val tickets: CapabilityTicketReader,
    private val epochs: CapabilityEpochStore,
    private val clock: WallClock,
    private val confirmationMillis: Long = 120_000,
) : CapabilityApprovals {
    private val mutex = Mutex()
    private val active = mutableMapOf<RuntimeTaskGeneration, Call>()
    private val changed = MutableStateFlow(Any())

    override fun pending(): Flow<List<CapabilityTicket>> = changed.map {
        mutex.withLock {
            tickets.pendingCapabilities().mapNotNull { stored ->
                val live = active[stored.address.task]?.ticket
                stored.takeIf { live?.address == stored.address }?.copy(reviewText = live?.reviewText)
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun <T> execute(
        context: RuntimeTaskContext,
        operation: RuntimeRecordKey,
        binding: CapabilityBinding,
        reviewText: String? = null,
        perform: suspend () -> T,
    ): CapabilityOutcome<T> = coroutineScope {
        val task = RuntimeTaskGeneration(context.taskId, context.generation)
        val id = RuntimeRecordKey.parse("cap.${UUID.randomUUID()}")
        val call = Call(CapabilityTicket(CapabilityAddress(task, id), operation, binding, clock.now(), reviewText), coroutineContext.job)
        var endCode = "cancelled"
        try {
            register(call, context)
            val outcome = runCall(call, context, perform)
            endCode = when (outcome) {
                is CapabilityOutcome.Blocked -> outcome.reason.name.lowercase()
                is CapabilityOutcome.Performed -> "returned"
            }
            outcome
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            endCode = "failed"
            throw failure
        } finally {
            finish(call, endCode)
        }
    }

    override suspend fun decide(address: CapabilityAddress, allow: Boolean): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            val call = active[address.task] ?: return@withLock false
            if (call.ticket.address != address || call.answer.isCompleted || !call.owner.isActive) return@withLock false
            if (epochs.current(address.task.taskId) != call.epoch) return@withLock false
            val state = if (allow) RuntimeApprovalState.APPROVED else RuntimeApprovalState.DENIED
            if (apply(call, RuntimeProjectionChange.ResolveApproval(address.id, state)) != RuntimeProjectionResult.APPLIED) {
                return@withLock false
            }
            call.answer.complete(allow)
            changed.value = Any()
            true
        }
    }

    override suspend fun revoke(task: RuntimeTaskGeneration): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        var owner: Job? = null
        try {
            mutex.withLock {
                val call = active[task] ?: return@withLock
                owner = call.owner
                try {
                    epochs.revokeTask(task.taskId, clock.now())
                    audit(call, "revoke", "revoked")
                } finally {
                    call.owner.cancel(CancellationException("capability revoked"))
                }
            }
        } finally {
            owner?.join()
        }
        owner != null
    }

    private suspend fun register(call: Call, context: RuntimeTaskContext) = mutex.withLock {
        val (task, id) = call.ticket.address
        check(task !in active && active.size < MAX_ACTIVE) { "capability queue is full or the task is already waiting" }
        call.epoch = epochs.current(task.taskId)
        context.transition(RuntimeState.WAITING_APPROVAL)
        val stored = apply(call, RuntimeProjectionChange.RequestApproval(id, call.ticket.operation, call.ticket.binding))
        check(stored == RuntimeProjectionResult.APPLIED) { "capability request did not commit" }
        active[task] = call
        changed.value = Any()
    }

    private suspend fun <T> runCall(call: Call, context: RuntimeTaskContext, perform: suspend () -> T): CapabilityOutcome<T> {
        val allow = withTimeoutOrNull(confirmationMillis) { call.answer.await() }
            ?: return CapabilityOutcome.Blocked(CapabilityBlock.EXPIRED)
        if (!allow) return CapabilityOutcome.Blocked(CapabilityBlock.DENIED)
        if (!begin(call, context)) return CapabilityOutcome.Blocked(CapabilityBlock.STALE)
        return withTimeoutOrNull(call.ticket.binding.budget.durationMillis) {
            currentCoroutineContext().ensureActive()
            CapabilityOutcome.Performed(perform())
        } ?: CapabilityOutcome.Blocked(CapabilityBlock.EXECUTION_TIMEOUT)
    }

    private suspend fun begin(call: Call, context: RuntimeTaskContext): Boolean = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (epochs.current(context.taskId) != call.epoch) return@withLock false
        // Recheck the runtime lease and task cancellation before handing anything to the adapter.
        context.transition(RuntimeState.RUNNING)
        audit(call, "start", "authorized")
        true
    }

    private suspend fun finish(call: Call, code: String) = withContext(NonCancellable) {
        mutex.withLock {
            val (task, id) = call.ticket.address
            if (active[task] !== call) return@withLock
            try {
                if (!call.answer.isCompleted) {
                    apply(call, RuntimeProjectionChange.ResolveApproval(id, RuntimeApprovalState.CANCELLED))
                }
                audit(call, "end", code)
            } finally {
                active.remove(task)
                changed.value = Any()
            }
        }
    }

    private suspend fun apply(call: Call, change: RuntimeProjectionChange): RuntimeProjectionResult =
        projections.apply(call.ticket.address.task, change, clock.now())

    private suspend fun audit(call: Call, label: String, code: String) {
        val id = RuntimeRecordKey.parse("${call.ticket.address.id.value}.$label")
        val result = apply(call, RuntimeProjectionChange.RecordDecision(id, RuntimeRecordKey.parse(code)))
        check(result == RuntimeProjectionResult.APPLIED || result == RuntimeProjectionResult.UNCHANGED) { "capability audit rejected" }
    }

    private class Call(val ticket: CapabilityTicket, val owner: Job) {
        lateinit var epoch: CapabilityEpoch
        val answer = CompletableDeferred<Boolean>()
    }

    private companion object {
        const val MAX_ACTIVE = 64
    }
}
