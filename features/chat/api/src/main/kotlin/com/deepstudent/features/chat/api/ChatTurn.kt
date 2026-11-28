package com.deepstudent.features.chat.api

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskKind
import java.time.Instant
import java.util.UUID

/** One isolated streaming chat turn. The runtime owns the task identity and generation. */
data class ChatTurnRequest(
    val taskId: TaskId,
    val generation: Long,
    val sessionId: String,
    val endpoint: String,
    val prompt: String,
    val model: String? = null,
    val credentialReference: String? = null,
) {
    init {
        require(generation > 0) { "invalid chat turn generation" }
        require(endpoint.length <= 2048 && endpoint.isNotBlank()) { "invalid chat endpoint" }
        require(prompt.isNotBlank() && prompt.length <= 32_768) { "invalid chat prompt" }
        require(model == null || model.isNotBlank()) { "invalid chat model" }
        require(credentialReference == null || credentialReference.isNotBlank()) { "invalid credential reference" }
        require(sessionId == ChatTurnContract.sessionId(taskId)) { "chat turn session does not match task" }
    }

    /** Endpoint, prompt and credential reference must never reach logs through this value. */
    override fun toString(): String = "ChatTurnRequest(redacted)"
}

/** Provider-reported identity. None of these fields is persisted as credentials. */
data class ChatTurnUsageMetadata(val provider: String, val model: String, val configId: String, val adapter: String) {
    init {
        require(provider.isNotBlank() && model.isNotBlank() && configId.isNotBlank() && adapter.isNotBlank()) {
            "invalid usage metadata"
        }
    }
}

/**
 * Provider-reported token values. Missing values stay null; callers must not turn them into
 * measurements. [totalTokens] is stored only when the provider reports it or both prompt and
 * completion are actually present.
 */
data class ChatTurnUsage(
    val metadata: ChatTurnUsageMetadata,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cachedTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
) {
    init {
        require(
            listOf(promptTokens, completionTokens, totalTokens, reasoningTokens, cachedTokens, cacheWriteTokens)
                .all { it == null || it >= 0 },
        ) { "invalid usage token count" }
    }

    override fun toString(): String = "ChatTurnUsage(redacted)"
}

/** Normalized live events. Transport failures throw; cancellation is always CancellationException. */
sealed interface ChatTurnEvent {
    data class Text(val delta: String) : ChatTurnEvent {
        override fun toString(): String = "ChatTurnEvent.Text(redacted)"
    }

    data class Usage(val usage: ChatTurnUsage) : ChatTurnEvent {
        override fun toString(): String = "ChatTurnEvent.Usage(redacted)"
    }

    data object Completed : ChatTurnEvent
}

fun interface ChatTurnEventSink {
    suspend fun emit(event: ChatTurnEvent)
}

/** Collects one turn and emits only normalized events. A clean return without Completed is a break. */
fun interface ChatTurnTransport {
    suspend fun collect(request: ChatTurnRequest, sink: ChatTurnEventSink)
}

/** Losslessly reads runtime JSON; absent and explicit null optional values both decode to null. */
object ChatTurnPayloadDecoder {
    fun decode(payload: JsonValue, taskId: TaskId, generation: Long): ChatTurnRequest {
        val values = payload as? JsonValue.Object
            ?: throw IllegalArgumentException("invalid chat turn payload")
        val endpoint = JsonFields.string(values, "endpoint")
        val prompt = JsonFields.string(values, "prompt")
        val model = JsonFields.optionalString(values, "model")
        val credentialReference = JsonFields.optionalString(values, "credentialReference")
        return ChatTurnRequest(
            taskId = taskId,
            generation = generation,
            sessionId = ChatTurnContract.sessionId(taskId),
            endpoint = endpoint,
            prompt = prompt,
            model = model,
            credentialReference = credentialReference,
        )
    }
}

/** Frozen Android task kind for this unit. It is deliberately separate from S1 chat streams. */
object ChatTurnContract {
    val KIND: TaskKind = TaskKind.parse("chat.turn_stream")

    fun sessionId(taskId: TaskId): String = "sess_${taskId.value.removePrefix("task_")}"
}

/** Stable IDs derived only from task identity and generation. */
data class ChatTurnIds(val userId: String, val assistantId: String, val blockId: String) {
    companion object {
        fun of(taskId: TaskId, generation: Long): ChatTurnIds {
            require(generation > 0) { "invalid chat turn generation" }
            val user = UUID.nameUUIDFromBytes("${taskId.value}:$generation:user:message".encodeToByteArray())
            val assistant = UUID.nameUUIDFromBytes("${taskId.value}:$generation:assistant:message".encodeToByteArray())
            val block = UUID.nameUUIDFromBytes("${taskId.value}:$generation:assistant:block".encodeToByteArray())
            return ChatTurnIds("msg_$user", "msg_$assistant", "blk_$block")
        }
    }
}

/** Actual committed assistant content, not a runtime checkpoint projection. */
data class ChatTurnInFlight(val text: String, val blockStatus: String, val blockError: String? = null)

/** Narrow persistence boundary used by the backend pipeline and recovery handler. */
interface ChatTurnWritePort {
    suspend fun begin(request: ChatTurnRequest, startedAt: Instant)
    suspend fun append(request: ChatTurnRequest, text: String, firstChunkAt: Instant?, updatedAt: Instant)
    suspend fun complete(request: ChatTurnRequest, text: String, usage: ChatTurnUsage?, endedAt: Instant)
    suspend fun fail(request: ChatTurnRequest, text: String, usage: ChatTurnUsage?, endedAt: Instant)
    suspend fun cancel(request: ChatTurnRequest, text: String, usage: ChatTurnUsage?, endedAt: Instant)
    suspend fun interrupt(request: ChatTurnRequest, endedAt: Instant)
}

/** Recovery reads committed chat rows and may settle their running block without inventing a fact. */
interface ChatTurnRecoveryPort {
    suspend fun loadInFlight(taskId: TaskId, sessionId: String): ChatTurnInFlight?

    /** Generation-aware replay used by the recovery handler; default keeps the basic port narrow. */
    suspend fun loadInFlight(taskId: TaskId, generation: Long, sessionId: String): ChatTurnInFlight? = loadInFlight(taskId, sessionId)

    suspend fun settleInterrupted(taskId: TaskId, generation: Long, sessionId: String, at: Instant): RuntimeState?
}

object ChatTurnBusinessStates {
    /** Map only the authoritative Android fact state, never a chat block status. */
    fun fromFactStatus(status: String): RuntimeState? = when (status) {
        "success" -> RuntimeState.COMPLETED
        "error" -> RuntimeState.FAILED
        "cancelled" -> RuntimeState.CANCELLED
        else -> null
    }
}
