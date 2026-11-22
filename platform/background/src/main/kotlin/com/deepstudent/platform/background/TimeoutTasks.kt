package com.deepstudent.platform.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal object TimeoutTasks {
    fun request(host: RuntimeForegroundHost, duties: List<ForegroundDuty>) {
        duties.forEach { duty ->
            host.foregroundDuties.retire(duty.taskId, duty.generation)
            host.foregroundScope.launch(Dispatchers.IO) { checkpointThenCancel(host, duty) }
        }
    }

    private suspend fun checkpointThenCancel(host: RuntimeForegroundHost, duty: ForegroundDuty) {
        val log = ForegroundLog(host.foregroundLogger)
        val started = System.nanoTime()
        try {
            val committed = withTimeoutOrNull(CHECKPOINT_TIMEOUT_MS) {
                host.checkpointForegroundTask(duty)
                true
            } ?: false
            log.event("background.timeout", duty, if (committed) "checkpoint_committed" else "checkpoint_timed_out", elapsed(started))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            log.event("background.timeout", duty, "checkpoint_failed", elapsed(started))
        }
        cancelCurrent(host, duty, log)
    }

    private fun elapsed(started: Long): Long = ((System.nanoTime() - started).coerceAtLeast(0) / 1_000_000)

    private suspend fun cancelCurrent(host: RuntimeForegroundHost, duty: ForegroundDuty, log: ForegroundLog) {
        try {
            DutyCancellation.cancel(host, duty, log, "background.timeout.cancel")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            log.event("background.timeout", duty, "cancel_failed")
        }
    }

    private const val CHECKPOINT_TIMEOUT_MS = 2_000L
}
