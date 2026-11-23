package com.deepstudent.services.runtime.api

/**
 * Return true only with proof that this exact owner can no longer execute. A
 * merely expired lease is not proof. The proof must also exclude resurrection.
 */
fun interface DeadOwnerPredicate {
    fun isDead(snapshot: RuntimeSnapshot): Boolean
}

/** Receives a freshly reloaded authoritative snapshot and its persisted event cursor. */
fun interface RecoveryPublisher {
    suspend fun publish(snapshot: RuntimeSnapshot)
}

/** Inspection is read-only. In particular, it must not start external side effects. */
fun interface RecoveryHandler {
    suspend fun decide(snapshot: RuntimeSnapshot): RecoveryDecision
}

/** Resume is allowed only for idempotent or externally fenced side effects. */
sealed interface RecoveryDecision {
    data object Interrupted : RecoveryDecision
    data object ConfirmationRequired : RecoveryDecision
    data object Resume : RecoveryDecision

    /**
     * Leaves an already gated task untouched: no execution, terminal projection or fabricated
     * result. Recovery only releases its own claim, so `WAITING_APPROVAL`/`PAUSED` survive a
     * restart exactly as the approval flow or the user left them.
     */
    data object KeepPaused : RecoveryDecision
}

/** Each task kind has exactly one authoritative business source and recovery handler. */
data class RecoveryRegistration(val kind: TaskKind, val completionSource: BusinessCompletionSource, val handler: RecoveryHandler)
