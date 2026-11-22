package com.deepstudent.platform.background

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.TaskId
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local foreground responsibilities, never an execution or recovery ledger. */
class DutyRegistry {
    private val lock = Any()
    private val references = mutableMapOf<Long, ForegroundDuty>()
    private val state = MutableStateFlow<List<ForegroundDuty>>(emptyList())
    private var nextReference = 0L

    val duties: StateFlow<List<ForegroundDuty>> = state.asStateFlow()

    internal fun acquire(duty: ForegroundDuty): Closeable = synchronized(lock) {
        val existing = references.values.firstOrNull { it.taskId == duty.taskId }
        require(existing == null || existing == duty) { "conflicting task duty" }
        check(nextReference < Long.MAX_VALUE) { "duty reference limit reached" }
        val reference = ++nextReference
        references[reference] = duty
        publish()
        Closeable { release(reference) }
    }

    /** Terminal runtime facts retire all references for this task generation. */
    internal fun retire(taskId: TaskId, generation: Long) = synchronized(lock) {
        if (references.entries.removeAll { it.value.taskId == taskId && it.value.generation == generation }) {
            publish()
        }
    }

    private fun release(reference: Long) = synchronized(lock) {
        if (references.remove(reference) != null) publish()
    }

    private fun publish() {
        state.value = references.values.distinct().toList()
    }
}

data class ForegroundDuty(
    val taskId: TaskId,
    val generation: Long,
    val correlationId: CorrelationId?,
    val purpose: ForegroundPurpose,
) {
    init {
        require(generation > 0) { "invalid task generation" }
    }
}

/** Only actual user-initiated transfer/synchronization work qualifies here. */
enum class ForegroundPurpose {
    DATA_SYNC,
}
