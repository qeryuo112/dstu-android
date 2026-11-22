package com.deepstudent.app.runtime

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.platform.background.ForegroundTaskPorts
import com.deepstudent.services.runtime.api.RuntimeCancelResult
import com.deepstudent.services.runtime.api.RuntimeEvent
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeTaskCanceller
import com.deepstudent.services.runtime.api.RuntimeTaskCatalog
import com.deepstudent.services.runtime.api.RuntimeTaskObserver
import com.deepstudent.services.runtime.api.RuntimeTaskQuery
import com.deepstudent.services.runtime.api.TaskCursor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Services can receive a cold-start intent before the application recovery pass finishes. */
internal class ForegroundRuntimePorts(private val load: suspend () -> MainRuntimeResources) :
    RuntimeTaskQuery, RuntimeTaskCatalog, RuntimeTaskCanceller, RuntimeTaskObserver {
    val ports = ForegroundTaskPorts(this, this, this, this)

    override suspend fun snapshot(taskId: TaskId): RuntimeSnapshot? = load().controller.snapshot(taskId)

    override suspend fun events(taskId: TaskId, after: TaskCursor): List<RuntimeEvent> =
        load().controller.events(taskId, after)

    override suspend fun activeSnapshots(afterTaskId: String?, limit: Int): List<RuntimeSnapshot> =
        load().controller.activeSnapshots(afterTaskId, limit)

    override suspend fun cancel(taskId: TaskId): RuntimeCancelResult = load().controller.cancel(taskId)

    override suspend fun cancelIfGeneration(taskId: TaskId, generation: Long): RuntimeCancelResult =
        load().controller.cancelIfGeneration(taskId, generation)

    override fun observe(taskId: TaskId, after: TaskCursor?): Flow<RuntimeEvent> = flow {
        emitAll(load().controller.observe(taskId, after))
    }
}
