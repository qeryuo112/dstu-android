package com.deepstudent.features.chat.api

import com.deepstudent.core.common.id.TaskId
import kotlinx.coroutines.flow.Flow

data class ChatStreamRequest(
    val endpoint: String,
    val prompt: String,
    val model: String? = null,
    val credentialReference: String? = null,
) {
    init {
        require(endpoint.length <= 2048 && endpoint.isNotBlank())
        require(prompt.isNotBlank() && prompt.length <= 32_768)
        require(model == null || (model.isNotBlank() && model.length <= 512)) { "invalid chat model" }
        require(credentialReference == null || (model != null && CREDENTIAL.matches(credentialReference))) {
            "invalid chat credential reference"
        }
    }
    override fun toString(): String = "ChatStreamRequest(redacted)"

    private companion object {
        val CREDENTIAL = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
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
