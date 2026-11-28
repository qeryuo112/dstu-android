package com.deepstudent.services.runtime.api

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import kotlinx.coroutines.flow.Flow

fun interface RuntimeTaskCanceller {
    suspend fun cancel(taskId: TaskId): RuntimeCancelResult

    /** The default refuses conditional cancellation; implementations must never ignore generation. */
    suspend fun cancelIfGeneration(taskId: TaskId, generation: Long): RuntimeCancelResult = RuntimeCancelResult.StaleGeneration(taskId)
}

interface RuntimeTaskQuery {
    suspend fun snapshot(taskId: TaskId): RuntimeSnapshot?
    suspend fun events(taskId: TaskId, after: TaskCursor = TaskCursor.ZERO): List<RuntimeEvent>
}

/** Stable task-id ordered pages of non-terminal local tasks for foreground reconstruction. */
interface RuntimeTaskCatalog {
    suspend fun activeSnapshots(afterTaskId: String? = null, limit: Int = 100): List<RuntimeSnapshot>
}

/** Observation only; cancelling collection never cancels the task. */
fun interface RuntimeTaskObserver {
    fun observe(taskId: TaskId, after: TaskCursor?): Flow<RuntimeEvent>
}

/** A state stream may be converted to a UI-owned StateFlow without owning execution. */
fun interface RuntimeSnapshotObserver {
    fun observeSnapshots(taskId: TaskId): Flow<RuntimeSnapshot?>
}

/** The implementation belongs to the task owner, not to a UI collector. */
interface RuntimeTaskContext {
    val taskId: TaskId
    val generation: Long

    /**
     * Checkpoint committed by an earlier generation-preserving execution attempt, if any.
     * Scheduled handlers use it to continue the same task instead of starting a new one.
     */
    val resumeCheckpoint: JsonValue? get() = null

    /** Durable user cancellation, distinct from supervisor/system yield cancellation. */
    val cancelRequested: Boolean get() = false

    suspend fun checkpoint(value: JsonValue?)
    suspend fun transition(state: RuntimeState)
    suspend fun renewLease()
}

/** Implementations must finish all side effects before returning and honor cancellation/fencing. */
fun interface RuntimeTaskHandler {
    suspend fun execute(context: RuntimeTaskContext, payload: JsonValue)
}
