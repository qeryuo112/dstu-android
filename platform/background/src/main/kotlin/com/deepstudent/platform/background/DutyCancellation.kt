package com.deepstudent.platform.background

import com.deepstudent.services.runtime.api.RuntimeCancelResult

internal object DutyCancellation {
    suspend fun cancel(host: RuntimeForegroundHost, duty: ForegroundDuty, log: ForegroundLog, event: String) {
        val snapshot = host.foregroundTasks.query.snapshot(duty.taskId) ?: return
        if (snapshot.ownership.generation != duty.generation || snapshot.state.terminal) return
        val result = host.foregroundTasks.canceller.cancelIfGeneration(duty.taskId, duty.generation)
        val state = when (result) {
            is RuntimeCancelResult.Requested -> "requested"
            is RuntimeCancelResult.AlreadyTerminal -> "already_terminal"
            is RuntimeCancelResult.NotFound -> "not_found"
            is RuntimeCancelResult.StaleGeneration -> "stale_generation"
        }
        log.event(event, duty, state)
    }
}
