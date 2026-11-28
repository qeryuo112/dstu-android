package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.api.BlockDetails
import com.deepstudent.features.chat.api.ChatBlock
import com.deepstudent.features.chat.api.ChatConversation
import com.deepstudent.features.chat.api.ChatMessage
import com.deepstudent.features.chat.api.ChatRepository
import com.deepstudent.features.chat.api.ChatSession
import com.deepstudent.features.chat.api.MessagePayload
import com.deepstudent.services.providers.api.ProviderCallAccess
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderConfigService
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ProviderTool
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal class MainProviderTurn(
    val selection: ProviderSelection,
    val sessionId: String,
    val user: ProviderMessage,
    val tools: List<ProviderTool> = emptyList(),
) {
    init {
        require(sessionId.isNotBlank() && user.role == ProviderRole.USER)
    }
}

/** Uses the existing conversation transaction; provider-private state is never sent to a visible event sink. */
internal class MainProviderConversations(
    private val repository: ChatRepository,
    private val config: ProviderConfigService,
    private val access: ProviderCallAccess,
) {
    private val writes = Array(16) { Mutex() }

    suspend fun call(
        turn: MainProviderTurn,
        cancellation: ProviderCancellation = ProviderCancellation { false },
        events: ProviderEventSink = ProviderEventSink {},
    ): ProviderResult<ProviderStreamResult> = writes[(turn.sessionId.hashCode() and Int.MAX_VALUE) % writes.size].withLock {
        if (cancellation.isCancelled()) return@withLock ProviderResult.Cancelled
        val existing = repository.load(turn.sessionId)
        val capture = MainProviderReplyHistory()
        val result = config.call(
            turn.selection,
            providerMessages(existing) + turn.user,
            turn.tools,
            turn.sessionId,
            cancellation,
            access,
            ProviderEventSink { event -> capture.consume(event, events) },
        )
        if (result !is ProviderResult.Success) return@withLock result
        if (cancellation.isCancelled()) return@withLock ProviderResult.Cancelled
        if (result.value.stopReason == ProviderStopReason.STOP) {
            repository.save(completedConversation(turn, existing, result.value, capture.metadata(result.value.nativeHistory)))
        }
        // A terminal tool declaration alone is not a completed conversation round; never persist an orphan call.
        events.emit(ProviderStreamEvent.Completed(result.value.stopReason, result.value.usage))
        result
    }
}

private fun completedConversation(
    turn: MainProviderTurn,
    existing: ChatConversation?,
    reply: ProviderStreamResult,
    metadata: JsonValue.Object?,
): ChatConversation {
    val now = Instant.now()
    val session = existing?.session?.copy(updatedAt = now.toString())
        ?: ChatSession(turn.sessionId, "general_chat", now.toString(), now.toString())
    val previousTimestamp = existing?.messages?.maxOfOrNull { it.timestamp } ?: 0L
    val timestamp = maxOf(now.toEpochMilli(), previousTimestamp + 1)
    val attachments = turn.user.nativeContent?.imageDataUrls.orEmpty()
    val user = conversationMessage(session.id, ProviderRole.USER, turn.user.text, timestamp, userPayload(attachments))
    val assistant = conversationMessage(session.id, ProviderRole.ASSISTANT, reply.text, timestamp + 1, MessagePayload(metadata = metadata))
    return ChatConversation(session, listOf(user.first, assistant.first), listOf(user.second, assistant.second))
}

private fun userPayload(images: List<String>): MessagePayload {
    val attachments = images.mapNotNull(::inlineAttachment)
    return MessagePayload(attachments = attachments.takeIf { it.isNotEmpty() }?.let(JsonValue::array))
}

private fun inlineAttachment(url: String): JsonValue.Object? {
    val meta = url.substringBefore(',')
    if (!meta.startsWith("data:image/") || !meta.endsWith(";base64")) return null
    val mime = meta.removePrefix("data:").removeSuffix(";base64")
    val bytes = runCatching { Base64.getDecoder().decode(url.substringAfter(',', "")) }.getOrNull() ?: return null
    if (bytes.isEmpty()) return null
    return JsonValue.Object.Builder().put("id", "att_${UUID.randomUUID()}").put("type", "image")
        .put("name", "image").put("mimeType", mime).put("size", bytes.size).put("status", "ready").put("previewUrl", url).build()
}

private fun conversationMessage(
    sessionId: String,
    role: ProviderRole,
    text: String,
    timestamp: Long,
    payload: MessagePayload,
): Pair<ChatMessage, ChatBlock> {
    val messageId = "msg_${UUID.randomUUID()}"
    val blockId = "blk_${UUID.randomUUID()}"
    val row = ChatMessage(messageId, sessionId, role.wireName, listOf(blockId), timestamp, payload = payload)
    return row to ChatBlock(blockId, messageId, "content", "success", 0, BlockDetails(content = text))
}
