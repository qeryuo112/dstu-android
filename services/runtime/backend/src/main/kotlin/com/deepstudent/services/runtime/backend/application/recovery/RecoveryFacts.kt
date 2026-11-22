package com.deepstudent.services.runtime.backend.application.recovery

import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.backend.leaseToken

/** Business facts are queried before any unfinished work is considered for resumption. */
internal class RecoveryFacts(private val session: RecoverySession) {
    private val claims = RecoveryClaims(session)

    suspend fun reconcilePending() {
        for (source in session.registry.sources) {
            if (!drain(source)) session.unavailableSources.add(source)
        }
    }

    private suspend fun drain(source: BusinessCompletionSource): Boolean {
        val seen = mutableSetOf<String>()
        repeat(MAX_PAGES) {
            var page: List<BusinessCompletion>? = null
            val read = session.attempt(RecoveryPhase.OUTBOX) {
                page = source.pending(session.consumerId, session.batchSize)
                require(page!!.size <= session.batchSize) { "invalid completion page" }
                true
            }
            if (!read) return false
            val facts = requireNotNull(page)
            if (facts.isEmpty()) return true
            if (facts.any { !seen.add(it.factId) }) return false
            for (fact in facts) {
                if (!projectPending(source, fact)) {
                    session.blocked.add(fact.taskId)
                    return false
                }
            }
        }
        return false
    }

    private suspend fun projectPending(source: BusinessCompletionSource, fact: BusinessCompletion): Boolean {
        var snapshot = session.claims[fact.taskId]
        if (snapshot == null) {
            val found = session.attempt(RecoveryPhase.LOOKUP) {
                snapshot = session.actions.ledger.snapshot(fact.taskId)
                snapshot != null
            }
            if (!found) return false
            val candidate = requireNotNull(snapshot)
            if (session.registry[candidate.kind]?.completionSource !== source) return false
            snapshot = claims.claim(candidate, forFact = true) ?: return false
        }
        val claim = requireNotNull(snapshot)
        if (session.registry[claim.kind]?.completionSource !== source) return false
        return projectAndAcknowledge(source, claim, fact)
    }

    suspend fun findCompletions() {
        for ((taskId, snapshot) in session.claims.toMap()) {
            if (taskId in session.settled || taskId in session.blocked) continue
            findCompletion(snapshot)
        }
    }

    private suspend fun findCompletion(snapshot: RuntimeSnapshot) {
            val taskId = snapshot.taskId
            val registration = session.registry[snapshot.kind]
            if (registration == null || registration.completionSource in session.unavailableSources) {
                session.blocked.add(taskId)
                return
            }
            var fact: BusinessCompletion? = null
            val found = session.attempt(RecoveryPhase.LOOKUP, snapshot) {
                fact = registration.completionSource.find(taskId, snapshot.ownership.generation)
                true
            }
            if (!found) {
                session.blocked.add(taskId)
            } else if (fact != null && !projectAndAcknowledge(registration.completionSource, snapshot, fact!!)) {
                session.blocked.add(taskId)
            }
    }

    private suspend fun projectAndAcknowledge(
        source: BusinessCompletionSource,
        snapshot: RuntimeSnapshot,
        fact: BusinessCompletion,
    ): Boolean {
        if (fact.taskId != snapshot.taskId || fact.generation != snapshot.ownership.generation) return false
        val claim = if (snapshot.leaseToken() == null &&
            (snapshot.execution.confirmationRequired || snapshot.state == com.deepstudent.services.runtime.api.RuntimeState.INTERRUPTED)
        ) {
            claims.claim(snapshot, forFact = true) ?: return false
        } else snapshot
        if (!claims.reconcile(claim, fact)) return false
        session.settled.add(snapshot.taskId)
        session.reconciled++
        return session.attempt(RecoveryPhase.OUTBOX, snapshot) {
            source.acknowledge(session.consumerId, fact.factId)
            true
        }
    }

    private companion object {
        const val MAX_PAGES = 10000
    }
}
