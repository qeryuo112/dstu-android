package com.deepstudent.services.runtime.backend.application.recovery

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.RecoveryPublisher
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import kotlinx.coroutines.CancellationException

internal class RecoverySession(
    val actions: RecoveryActions,
    val registry: RecoveryRegistry,
    private val publisher: RecoveryPublisher,
    val log: RecoveryLog,
    val batchSize: Int,
) {
    val claims = linkedMapOf<TaskId, RuntimeSnapshot>()
    val settled = mutableSetOf<TaskId>()
    val transferred = mutableSetOf<TaskId>()
    val blocked = mutableSetOf<TaskId>()
    val dirty = linkedSetOf<TaskId>()
    val unavailableSources: MutableSet<BusinessCompletionSource> = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<BusinessCompletionSource, Boolean>(),
    )
    val consumerId = "runtime.recovery"
    var reconciled = 0
    var resumed = 0
    var interrupted = 0
    var confirmationRequired = 0
    var cancelled = 0
    var failures = 0

    suspend fun claimCandidates(): Boolean = RecoveryClaims(this).claimCandidates()
    suspend fun reconcilePending() = RecoveryFacts(this).reconcilePending()
    suspend fun findCompletions() = RecoveryFacts(this).findCompletions()
    suspend fun resolveUnfinished() = RecoveryResolution(this).resolveUnfinished()

    suspend fun finish() {
        RecoveryClaims(this).releaseClaims()
        for (taskId in dirty) {
            attempt(RecoveryPhase.PUBLISH, claims[taskId]) {
                val current = actions.ledger.snapshot(taskId) ?: return@attempt false
                publisher.publish(current)
                true
            }
        }
    }

    suspend fun attempt(
        phase: RecoveryPhase,
        snapshot: RuntimeSnapshot? = null,
        action: suspend () -> Boolean,
    ): Boolean {
        val started = log.start()
        return try {
            val success = action()
            log.record(phase, if (success) RecoveryOutcome.APPLIED else RecoveryOutcome.BLOCKED, started, snapshot)
            success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failures++
            log.record(phase, RecoveryOutcome.FAILED, started, snapshot)
            false
        }
    }

    fun report() = RecoveryReport(
        claims.size, reconciled, resumed, interrupted, confirmationRequired, cancelled, blocked.size, failures,
    )
}
