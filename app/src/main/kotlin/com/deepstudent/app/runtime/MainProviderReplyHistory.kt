package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.api.ChatBlock
import com.deepstudent.features.chat.api.ChatConversation
import com.deepstudent.features.chat.api.ChatMessage
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderNativeHistory
import com.deepstudent.services.providers.api.ProviderNativeMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderResponsesHistory
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ResponsesMetadataKind

/** Responses items use the existing desktop message metadata, never a new database column. */
internal class MainProviderReplyHistory {
    private var reasoning: JsonValue.Object? = null
    private var scope: String? = null
    private val search = LinkedHashSet<JsonValue.Object>()

    suspend fun consume(event: ProviderStreamEvent, visible: ProviderEventSink) {
        when (event) {
            is ProviderStreamEvent.ResponsesMetadata -> collect(event)
            is ProviderStreamEvent.Completed -> Unit
            else -> visible.emit(event)
        }
    }

    fun metadata(native: ProviderNativeHistory?): JsonValue.Object? {
        val builder = JsonValue.Object.Builder()
        scope?.let { builder.put("stateScope", it) }
        reasoning?.let { builder.put(RESPONSE_ITEMS, JsonValue.obj(FINAL_ITEM to it)) }
        if (search.isNotEmpty()) builder.put(SEARCH_ITEMS, JsonValue.array(search.toList()))
        native?.let { nativeMetadata(it)?.let { state -> builder.put("providerState", state) } }
        return builder.build().takeUnless(JsonValue.Object::isEmpty)
    }

    private fun collect(event: ProviderStreamEvent.ResponsesMetadata) {
        val target = event.stateScope ?: return
        if (scope != null && scope != target) return
        val item = runCatching { JsonValue.parseObject(event.rawJson) }.getOrNull() ?: return
        when (event.kind) {
            ResponsesMetadataKind.REASONING_ITEM -> reasoning = item
            ResponsesMetadataKind.WEB_SEARCH -> search += item
            ResponsesMetadataKind.ANNOTATIONS -> return
        }
        scope = target
    }
}

internal fun providerMessages(conversation: ChatConversation?): List<ProviderMessage> {
    if (conversation == null) return emptyList()
    val blocks = conversation.blocks.filter { it.storage.deletedAt == null }.associateBy(ChatBlock::id)
    return conversation.messages.filter { it.storage.deletedAt == null }.sortedBy(ChatMessage::timestamp).flatMap { message ->
        val parts = selectedBlocks(message, blocks)
        val text = parts.filter { it.type == "content" && it.status == "success" }.joinToString("") { it.details.content.orEmpty() }
        storedToolHistory(message, parts) + listOfNotNull(readProviderMessage(message, text))
    }
}

private fun selectedBlocks(message: ChatMessage, blocks: Map<String, ChatBlock>): List<ChatBlock> {
    val active = message.variantItems()?.firstOrNull { it.id == message.links.activeVariantId }?.blockIds
    return (active ?: message.blockIds).mapNotNull(blocks::get).filter { it.messageId == message.id }
}

private fun readProviderMessage(message: ChatMessage, text: String): ProviderMessage? = when (message.role) {
    "user" -> readUser(message, text)
    "assistant" -> readAssistant(message, text)
    else -> null
}

private fun readUser(message: ChatMessage, text: String): ProviderMessage? {
    val attachments = message.payload.attachments as? JsonValue.Array
    val images = attachments?.values.orEmpty().mapNotNull { value ->
        val objectValue = value as? JsonValue.Object ?: return@mapNotNull null
        if (objectValue["type"] != JsonValue.text("image")) return@mapNotNull null
        (objectValue["previewUrl"] as? JsonValue.Text)?.value?.takeIf {
            it.startsWith("data:image/") && it.length <= ProviderNativeMessage.MAX_IMAGE_DATA_URL_CHARS
        }
    }.take(ProviderNativeMessage.MAX_IMAGE_DATA_URLS)
    if (text.isEmpty() && images.isEmpty()) return null
    val native = images.takeIf { it.isNotEmpty() }?.let { ProviderNativeMessage(imageDataUrls = it) }
    return ProviderMessage(ProviderRole.USER, text, nativeContent = native)
}

private fun readAssistant(message: ChatMessage, text: String): ProviderMessage? {
    val meta = message.payload.metadata as? JsonValue.Object
    val responses = readResponses(meta)
    val native = readNative(meta)?.let { ProviderNativeMessage(state = it) }
    if (text.isEmpty() && responses == null && native == null) return null
    return ProviderMessage(ProviderRole.ASSISTANT, text, responses = responses, nativeContent = native)
}

private fun nativeMetadata(native: ProviderNativeHistory): JsonValue.Object? {
    val items = runCatching { JsonValue.parse(native.payloadJson) }.getOrNull() ?: return null
    return JsonValue.obj("stateScope" to JsonValue.text(native.stateScope), "items" to items)
}

private fun readNative(meta: JsonValue.Object?): ProviderNativeHistory? {
    val native = meta?.get("providerState") as? JsonValue.Object ?: return null
    val scope = (native["stateScope"] as? JsonValue.Text)?.value ?: return null
    val items = native["items"] ?: return null
    val tuple = runCatching { JsonValue.parse(scope) as? JsonValue.Array }.getOrNull()
    val protocol = (tuple?.values?.getOrNull(3) as? JsonValue.Text)?.value
    val dialect = ProviderProtocol.entries.firstOrNull { it.wireName == protocol } ?: return null
    return runCatching { ProviderNativeHistory(dialect, scope, items.literal()) }.getOrNull()
}

private fun readResponses(meta: JsonValue.Object?): ProviderResponsesHistory? {
    val scope = (meta?.get("stateScope") as? JsonValue.Text)?.value ?: return null
    val reasoning = (meta[RESPONSE_ITEMS] as? JsonValue.Object)?.get(FINAL_ITEM) as? JsonValue.Object
    val search = meta[SEARCH_ITEMS] as? JsonValue.Array
    if (reasoning == null && search == null) return null
    return ProviderResponsesHistory(reasoningItemJson = reasoning?.literal(), webSearchItemsJson = search?.literal(), stateScope = scope)
}

private const val RESPONSE_ITEMS = "responseReasoningItems"
private const val SEARCH_ITEMS = "openai_responses_web_search_items"
private const val FINAL_ITEM = "__final_assistant__"
