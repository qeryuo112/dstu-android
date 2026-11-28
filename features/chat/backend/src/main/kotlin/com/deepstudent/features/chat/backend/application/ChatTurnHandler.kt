package com.deepstudent.features.chat.backend.application

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.api.ChatTurnContract
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import com.deepstudent.services.runtime.api.RuntimeTaskHandler
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission

/** New single-turn kind; S1 RuntimeChatStreams.KIND and its endpoint+prompt payload stay untouched. */
class ChatTurnHandler(private val pipeline: ChatTurnPipeline) : RuntimeTaskHandler {
    override suspend fun execute(context: RuntimeTaskContext, payload: JsonValue) = pipeline.execute(context, payload)

    companion object {
        val KIND: TaskKind = ChatTurnContract.KIND
        fun submission(request: com.deepstudent.features.chat.api.ChatTurnRequest): TaskSubmission = TaskSubmission(
            KIND,
            JsonValue.Object.Builder()
                .put("endpoint", request.endpoint).put("prompt", request.prompt)
                .apply {
                    request.model?.let { put("model", it) }
                    request.credentialReference?.let { put("credentialReference", it) }
                }.build(),
        )
    }
}
