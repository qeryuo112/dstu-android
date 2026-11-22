package com.deepstudent.services.backup.data

internal class RestoreTrace(
    private val operationId: String,
    private val audit: RestoreAudit,
    val hook: RestoreCheckpointHook,
    private val cancelled: () -> Boolean,
) {
    var currentPhase: RestorePhase = RestorePhase.ACQUIRE
        private set
    private var phaseStarted = System.nanoTime()

    fun enter(phase: RestorePhase) {
        currentPhase = phase
        phaseStarted = System.nanoTime()
        audit.record(RestoreEvent(operationId, phase, RestoreStatus.STARTED, 0))
        checkCancelled()
    }

    fun succeeded() = audit.record(RestoreEvent(operationId, currentPhase, RestoreStatus.SUCCEEDED, elapsed()))
    fun failed(error: RestoreError) = audit.record(RestoreEvent(operationId, currentPhase, RestoreStatus.FAILED, elapsed(), error))
    fun checkpoint(checkpoint: RestoreCheckpoint) { hook.reached(checkpoint); checkCancelled() }
    fun checkCancelled() { if (cancelled()) throw RestoreFailure(RestoreError.CANCELLED) }
    private fun elapsed(): Long = (System.nanoTime() - phaseStarted) / 1_000_000
}
