package com.deepstudent.platform.background

import com.deepstudent.services.runtime.api.RuntimeTaskObserver
import com.deepstudent.services.runtime.api.RuntimeTaskQuery
import com.deepstudent.services.runtime.api.RuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

internal class DutyObserver(
    private val registry: DutyRegistry,
    private val query: RuntimeTaskQuery,
    private val observer: RuntimeTaskObserver,
    private val log: ForegroundLog,
) {
    suspend fun watch(duty: ForegroundDuty, onState: suspend (RuntimeState) -> Unit) {
        while (true) {
            try {
                if (observeOnce(duty, onState)) return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                log.event("background.observe", duty, "retry")
            }
            delay(RETRY_DELAY_MS)
        }
    }

    private suspend fun observeOnce(duty: ForegroundDuty, onState: suspend (RuntimeState) -> Unit): Boolean {
        val snapshot = query.snapshot(duty.taskId)
        if (snapshot == null || snapshot.ownership.generation != duty.generation || snapshot.state.terminal) {
            registry.retire(duty.taskId, duty.generation)
            log.event("background.duty", duty, "retired")
            return true
        }
        onState(snapshot.state)
        observer.observe(duty.taskId, snapshot.cursor).collect { event ->
            if (event.taskId != duty.taskId || event.generation != duty.generation) return@collect
            if (event.state.terminal) {
                registry.retire(duty.taskId, duty.generation)
            } else {
                onState(event.state)
            }
        }
        return false
    }

    private companion object {
        const val RETRY_DELAY_MS = 1_000L
    }
}
