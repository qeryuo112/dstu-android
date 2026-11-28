package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.api.ChatBlock
import com.deepstudent.features.chat.api.ChatMessage
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderNativeHistory
import com.deepstudent.services.providers.api.ProviderNativeMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderResponsesHistory
import com.deepstudent.services.providers.api.ProviderRole

/** Read completed tool pairs from desktop-compatible rows; declarations alone never become results. */
internal fun storedToolHistory(message: ChatMessage, blocks: List<ChatBlock>): List<ProviderMessage> {
    if (message.role != ProviderRole.ASSISTANT.wireName) return emptyList()
    val meta = message.payload.metadata as? JsonValue.Object
    val results = (meta?.get("toolResults") as? JsonValue.Array)?.values.orEmpty().filterIsInstance<JsonValue.Object>()
    val seen = mutableSetOf<String>()
    return blocks.flatMap { block ->
        val item = results.firstOrNull { it.string("blockId") == block.id || sameToolCall(block, it) }
        val round = completedToolRound(block, item) ?: return@flatMap emptyList()
        if (seen.add(round.id)) round.messages(meta, item) else emptyList()
    }
}

private fun sameToolCall(block: ChatBlock, item: JsonValue.Object): Boolean {
    val id = block.storage.toolCallId?.takeIf(String::isNotBlank) ?: return false
    return id == item.string("toolCallId")
}

private fun completedToolRound(block: ChatBlock, item: JsonValue.Object?): StoredToolRound? {
    if (block.type !in TOOL_BLOCKS || block.status !in TERMINAL_RESULTS) return null
    val name = block.details.toolName?.takeIf(String::isNotBlank) ?: item?.string("toolName") ?: return null
    val result = storedToolOutput(block, item) ?: return null
    val input = block.details.toolInput ?: item?.get("input") ?: JsonValue.Object.EMPTY
    return StoredToolRound(storedToolId(block, item), name, input, result, block.storage.roundText.orEmpty())
}

private fun storedToolId(block: ChatBlock, item: JsonValue.Object?): String =
    block.storage.toolCallId?.takeIf(String::isNotBlank) ?: item?.string("toolCallId") ?: "tc_${block.id.replace("blk_", "")}"

private fun storedToolOutput(block: ChatBlock, item: JsonValue.Object?): String? {
    val outcome = block.details.toolOutput ?: item?.get("output")
    val error = block.details.error ?: item?.string("error")
    if (block.status == "error" && error != null) return "Error: $error"
    return outcome?.literal()
}

private class StoredToolRound(val id: String, val name: String, val input: JsonValue, val output: String, val roundText: String) {
    fun messages(meta: JsonValue.Object?, item: JsonValue.Object?): List<ProviderMessage> {
        val state = meta?.get("providerState") as? JsonValue.Object
        val scope = state?.string("stateScope") ?: meta?.string("stateScope")
        val signature = item?.string("thoughtSignature")
        val reasoning = (meta?.get("responseReasoningItems") as? JsonValue.Object)?.get(id)
        return listOf(
            ProviderMessage(
                ProviderRole.ASSISTANT,
                roundText,
                toolCallsJson = JsonValue.array(listOf(toolCall())).literal(),
                reasoningContent = item?.string("reasoningContent"),
                responses = scopedReasoning(scope, reasoning),
                nativeContent = scopedSignature(scope, signature),
            ),
            ProviderMessage(ProviderRole.TOOL, output, name = name, toolCallId = id),
        )
    }

    private fun toolCall(): JsonValue.Object = JsonValue.Object.Builder().put("id", id).put("type", "function")
        .put("function", JsonValue.Object.Builder().put("name", name).put("arguments", input.literal()).build()).build()
}

private fun scopedReasoning(scope: String?, item: JsonValue?): ProviderResponsesHistory? {
    if (scope == null || item !is JsonValue.Object) return null
    return ProviderResponsesHistory(reasoningItemJson = item.literal(), stateScope = scope)
}

private fun scopedSignature(scope: String?, signature: String?): ProviderNativeMessage? {
    if (scope == null || signature == null) return null
    val payload = JsonValue.Object.Builder().put("thoughtSignature", signature).build().literal()
    return runCatching {
        ProviderNativeMessage(state = ProviderNativeHistory(ProviderProtocol.GOOGLE_GENERATE_CONTENT, scope, payload))
    }.getOrNull()
}

private fun JsonValue.Object.string(key: String): String? = (get(key) as? JsonValue.Text)?.value?.takeIf(String::isNotBlank)

private val TERMINAL_RESULTS = setOf("success", "error")
private val TOOL_BLOCKS = setOf(
    "mcp_tool", "ask_user", "memory", "web_search", "graph", "rag", "insight_recall", "academic_search", "sleep",
    "subagent_embed", "workbench_ops",
)
