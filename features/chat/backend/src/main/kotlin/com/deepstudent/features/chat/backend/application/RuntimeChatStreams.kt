package com.deepstudent.features.chat.backend.application

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.api.ChatStreamPhase
import com.deepstudent.features.chat.api.ChatStreamRequest
import com.deepstudent.features.chat.api.ChatStreamSnapshot
import com.deepstudent.features.chat.api.ChatStreams
import com.deepstudent.services.runtime.api.RuntimeSnapshotObserver
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskCanceller
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.api.TaskSubmitter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

class RuntimeChatStreams(
    private val submitter: TaskSubmitter,
    private val observer: RuntimeSnapshotObserver,
    private val canceller: RuntimeTaskCanceller,
) : ChatStreams {
    override suspend fun start(request: ChatStreamRequest): TaskId = submitter.submit(TaskSubmission(
        KIND, JsonValue.Object.Builder().put("endpoint", request.endpoint).put("prompt", request.prompt).build(),
    )).taskId

    override fun observe(taskId: TaskId): Flow<ChatStreamSnapshot> = observer.observeSnapshots(taskId)
        .filterNotNull().map { snapshot ->
            val text = ((snapshot.execution.checkpoint as? JsonValue.Object)?.get("text") as? JsonValue.Text)?.value.orEmpty()
            ChatStreamSnapshot(taskId, text, phase(snapshot.state))
        }

    override suspend fun cancel(taskId: TaskId) { canceller.cancel(taskId) }

    companion object { val KIND = TaskKind.parse("chat.minimal_stream") }
}

private fun phase(state: RuntimeState): ChatStreamPhase = when (state) {
    RuntimeState.COMPLETED -> ChatStreamPhase.Completed
    RuntimeState.CANCELLED -> ChatStreamPhase.Cancelled
    RuntimeState.FAILED -> ChatStreamPhase.Failed
    RuntimeState.INTERRUPTED -> ChatStreamPhase.Interrupted
    else -> ChatStreamPhase.Running
}
