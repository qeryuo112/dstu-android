package com.deepstudent.features.chat.backend.application

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.features.chat.api.ChatRepository
import com.deepstudent.features.chat.api.ChatStreamRequest
import com.deepstudent.features.chat.api.ChatStreamTransport
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import com.deepstudent.services.runtime.api.RuntimeTaskHandler
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ChatStreamHandler(
    private val transport: ChatStreamTransport,
    private val repository: ChatRepository,
    private val clock: WallClock = SystemWallClock,
) : RuntimeTaskHandler {
    override suspend fun execute(context: RuntimeTaskContext, payload: JsonValue) {
        val values = payload as? JsonValue.Object ?: error("invalid chat payload")
        val endpoint = (values["endpoint"] as? JsonValue.Text)?.value ?: error("missing endpoint")
        val prompt = (values["prompt"] as? JsonValue.Text)?.value ?: error("missing prompt")
        val startedAt = clock.now()
        val text = StringBuilder()
        transport.collect(ChatStreamRequest(endpoint, prompt)) { delta ->
            currentCoroutineContext().ensureActive()
            require(text.length + delta.length <= 262_144) { "chat text budget exceeded" }
            text.append(delta)
            context.checkpoint(JsonValue.Object.Builder().put("text", text.toString()).build())
        }
        context.checkpoint(JsonValue.Object.Builder().put("text", text.toString()).build())
        currentCoroutineContext().ensureActive()
        val endedAt = maxOf(startedAt, clock.now())
        repository.save(completedMinimalChat(context.taskId, prompt, text.toString(), startedAt, endedAt))
    }
}
