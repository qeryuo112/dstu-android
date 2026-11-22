package com.deepstudent.services.runtime.api

import com.deepstudent.core.common.id.TaskId
import kotlinx.coroutines.flow.Flow

/** Owns creation only. It does not execute or observe the task. */
fun interface TaskSubmitter {
    suspend fun submit(submission: TaskSubmission): TaskHandle
}

/** Owns cancellation requests; terminal facts remain queryable from the ledger. */
fun interface TaskCanceller {
    suspend fun cancel(taskId: TaskId, reason: String?): TaskCancelResult
}

/** Read side of the ledger. Implementations may snapshot a projection or the ledger. */
interface TaskQuery {
    suspend fun snapshot(taskId: TaskId): TaskSnapshot?

    suspend fun events(taskId: TaskId, after: TaskCursor? = null): List<TaskEvent>
}

/** Observation only; closing the flow does not cancel the task. */
fun interface TaskObserver {
    fun observe(taskId: TaskId, after: TaskCursor?): Flow<TaskEvent>
}
