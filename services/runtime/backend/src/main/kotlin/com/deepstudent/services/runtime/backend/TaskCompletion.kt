package com.deepstudent.services.runtime.backend

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.BusinessCompletionConsumers
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState

internal data class CompletionOutcome(val state: RuntimeState?, val event: String)

/** Business lookup and acknowledgement happen outside the runtime database transaction. */
internal class TaskCompletion(private val ledger: TaskLedger, private val handlers: HandlerRegistry, private val clock: WallClock) {
    suspend fun finish(snapshot: RuntimeSnapshot, token: LeaseToken, state: RuntimeState, error: DstuError?): CompletionOutcome {
        val source = handlers.completionSource(snapshot.kind)
        val fact = source?.find(snapshot.taskId, token.generation)
        if (source != null && fact == null && state == RuntimeState.COMPLETED) {
            return CompletionOutcome(null, "runtime.fact_pending")
        }
        val result = if (fact != null) {
            project(snapshot, token, fact, requireNotNull(source))
        } else {
            ledger.apply(snapshot.taskId, LedgerCommand.Finish(token, state, error, source != null), clock.now())
        }
        return when (result) {
            is LedgerResult.Applied -> CompletionOutcome(result.snapshot.state, "runtime.finished")
            is LedgerResult.Unchanged -> CompletionOutcome(result.snapshot.state, "runtime.finished")
            else -> CompletionOutcome(null, "runtime.finish_rejected")
        }
    }

    /**
     * Cooperative cleanup after a system stop yielded this execution. A committed business fact
     * is projected first; a durable user cancel is settled without rerunning; otherwise the
     * lease is released and the task stays non-terminal and resumable from its checkpoint.
     */
    suspend fun yield(snapshot: RuntimeSnapshot, token: LeaseToken): CompletionOutcome {
        val source = handlers.completionSource(snapshot.kind)
        val fact = try {
            source?.find(snapshot.taskId, token.generation)
        } catch (_: Exception) {
            null
        }
        if (fact != null && source != null) {
            val result = project(snapshot, token, fact, source)
            val state = when (result) {
                is LedgerResult.Applied -> result.snapshot.state
                is LedgerResult.Unchanged -> result.snapshot.state
                else -> null
            }
            return if (state != null) {
                CompletionOutcome(state, "runtime.scheduled_reconciled")
            } else {
                CompletionOutcome(null, "runtime.yield_rejected")
            }
        }
        val current = ledger.snapshot(snapshot.taskId)
        if (current?.execution?.cancelRequested == true) {
            return apply(snapshot.taskId, LedgerCommand.Finish(token, RuntimeState.CANCELLED), "runtime.scheduled_cancelled")
        }
        return apply(snapshot.taskId, LedgerCommand.Release(token), "runtime.scheduled_released")
    }

    private suspend fun apply(taskId: com.deepstudent.core.common.id.TaskId, command: LedgerCommand, event: String): CompletionOutcome =
        when (val result = ledger.apply(taskId, command, clock.now())) {
            is LedgerResult.Applied -> CompletionOutcome(result.snapshot.state, event)
            is LedgerResult.Unchanged -> CompletionOutcome(result.snapshot.state, event)
            else -> CompletionOutcome(null, "runtime.yield_rejected")
        }

    private suspend fun project(
        snapshot: RuntimeSnapshot,
        token: LeaseToken,
        fact: BusinessCompletion,
        source: BusinessCompletionSource,
    ): LedgerResult {
        val result = ledger.apply(snapshot.taskId, LedgerCommand.Reconcile(fact, token), clock.now())
        if (result is LedgerResult.Applied || result is LedgerResult.Unchanged) {
            source.acknowledge(BusinessCompletionConsumers.RUNTIME, fact.factId)
        }
        return result
    }
}
