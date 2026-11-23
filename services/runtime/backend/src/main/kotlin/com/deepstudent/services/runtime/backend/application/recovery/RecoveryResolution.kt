package com.deepstudent.services.runtime.backend.application.recovery

import com.deepstudent.services.runtime.api.RecoveryDecision
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.leaseToken

internal class RecoveryResolution(private val session: RecoverySession) {
    suspend fun resolveUnfinished() {
        for ((taskId, snapshot) in session.claims) {
            if (taskId in session.settled || taskId in session.blocked || snapshot.state.terminal) continue
            if (!resolve(snapshot)) session.blocked.add(taskId)
        }
    }

    private suspend fun resolve(snapshot: RuntimeSnapshot): Boolean {
        if (snapshot.execution.confirmationRequired) return true
        val handler = session.registry[snapshot.kind]?.handler ?: return false
        var decision: RecoveryDecision? = null
        if (!session.attempt(RecoveryPhase.DECIDE, snapshot) {
                decision = handler.decide(snapshot)
                true
            }
        ) {
            return false
        }
        return when (decision) {
            RecoveryDecision.Interrupted -> finish(snapshot, RuntimeState.INTERRUPTED)
            RecoveryDecision.ConfirmationRequired -> confirmation(snapshot)
            RecoveryDecision.Resume -> resume(snapshot)
            RecoveryDecision.KeepPaused -> true
            null -> false
        }
    }

    private suspend fun finish(snapshot: RuntimeSnapshot, state: RuntimeState): Boolean {
        val token = snapshot.leaseToken() ?: return false
        return session.attempt(RecoveryPhase.DECIDE, snapshot) {
            val now = session.actions.clock.now()
            val result = session.actions.ledger.apply(snapshot.taskId, LedgerCommand.Finish(token, state), now)
            if (result !is LedgerResult.Applied) return@attempt false
            session.dirty.add(snapshot.taskId)
            session.settled.add(snapshot.taskId)
            if (result.snapshot.state == RuntimeState.CANCELLED) session.cancelled++ else session.interrupted++
            true
        }
    }

    private suspend fun confirmation(snapshot: RuntimeSnapshot): Boolean {
        val token = snapshot.leaseToken() ?: return false
        return session.attempt(RecoveryPhase.DECIDE, snapshot) {
            val now = session.actions.clock.now()
            val result = session.actions.ledger.apply(snapshot.taskId, LedgerCommand.RequireConfirmation(token), now)
            if (result !is LedgerResult.Applied) return@attempt false
            session.dirty.add(snapshot.taskId)
            session.settled.add(snapshot.taskId)
            session.confirmationRequired++
            true
        }
    }

    private suspend fun resume(snapshot: RuntimeSnapshot): Boolean {
        if (snapshot.execution.cancelRequested || snapshot.state == RuntimeState.PAUSED ||
            snapshot.state == RuntimeState.WAITING_APPROVAL
        ) {
            return false
        }
        val token = snapshot.leaseToken() ?: return false
        return session.attempt(RecoveryPhase.DECIDE, snapshot) {
            if (!session.actions.supervisor.startClaimed(snapshot, token)) return@attempt false
            session.transferred.add(snapshot.taskId)
            session.dirty.add(snapshot.taskId)
            session.resumed++
            true
        }
    }
}
