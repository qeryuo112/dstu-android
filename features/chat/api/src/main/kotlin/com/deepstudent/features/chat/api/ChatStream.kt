package com.deepstudent.features.chat.api

import com.deepstudent.core.common.id.TaskId
import kotlinx.coroutines.flow.Flow

data class ChatStreamRequest(val endpoint: String, val prompt: String) {
    init {
        require(endpoint.length <= 2048 && endpoint.isNotBlank())
        require(prompt.isNotBlank() && prompt.length <= 32_768)
    }
    override fun toString(): String = "ChatStreamRequest(redacted)"
}

enum class ChatStreamPhase { Running, Completed, Cancelled, Failed, Interrupted }

data class ChatStreamSnapshot(val taskId: TaskId, val text: String, val phase: ChatStreamPhase) {
    override fun toString(): String = "ChatStreamSnapshot(phase=$phase)"
}

/** Observation belongs to the UI; execution and cancellation belong to the task owner. */
interface ChatStreams {
    suspend fun start(request: ChatStreamRequest): TaskId
    fun observe(taskId: TaskId): Flow<ChatStreamSnapshot>
    suspend fun cancel(taskId: TaskId)
}

fun interface ChatStreamTransport {
    suspend fun collect(request: ChatStreamRequest, emit: suspend (String) -> Unit)
}
