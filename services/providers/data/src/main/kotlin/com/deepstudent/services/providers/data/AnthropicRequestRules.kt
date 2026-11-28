package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderNativeHistory
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderTool
import java.util.Locale

/**
 * Request-adapter rules for the Anthropic pipeline, mirroring the frozen llm
 * layer (`llm/src/{adapters/anthropic,adapters/base,generation,question_authoring,
 * provider_quirks}.ts` at e6b2657fd59014a73f5bbb1d14c2db732cf23e87): sampling is
 * stripped first per generation, thinking forks adaptive/manual, generation
 * params ride the provider quirks, and configured extra options override last
 * like the frozen `Object.assign`.
 *
 * The Android runtime has no reasoning-level registry, so the frozen level map
 * stays identity, matching the signed-off S3.04/S3.05 rules. The desktop's
 * separate raw `effort` config field does not exist on Android, so effort is
 * derived from `reasoningEffort` / `thinkingBudget` only.
 */
internal class AnthropicRequestState(val request: ProviderAdapterRequest) {
    val resolved get() = request.resolved
    val model: String get() = resolved.profile.model
    val baseUrl: String get() = resolved.baseUrl
    val providerType: String get() = resolved.providerType.trim().lowercase(Locale.ROOT)
    val providerScope: String get() = resolved.providerScope.trim().lowercase(Locale.ROOT)
    val modelAdapter: String get() = resolved.profile.adapter.wireName.lowercase(Locale.ROOT)
    val parameters get() = resolved.runtime.parameters
    val reasoning get() = resolved.runtime.reasoning
    val isReasoning: Boolean get() = resolved.runtime.isReasoning
    val supportsReasoning: Boolean get() = resolved.runtime.features.reasoning
    val thinkingEnabled: Boolean get() = resolved.runtime.thinkingEnabled
    val includeThoughts: Boolean get() = reasoning.includeThoughts

    /** Frozen `getTrimmedEffort`; mapping lowercases again, so trimming is the only step. */
    val effort: String? get() = reasoning.reasoningEffort?.trim()?.takeIf(String::isNotEmpty)
}

internal object AnthropicRequestRules {
    /** Frozen question_authoring.applyReasoningConfig order: strip, reason, common. */
    fun applyReasoning(body: RequestBody, state: AnthropicRequestState) {
        if (claudeGeneration(state.model) == ClaudeGeneration.ADAPTIVE) {
            body.removeAll("temperature", "top_p", "presence_penalty", "frequency_penalty", "logprobs")
        }
        if (applyAdapterReasoning(body, state)) return
        applyCommonParams(body, state)
    }

    /** Frozen llm AnthropicAdapter.applyReasoningConfig; true skips common params (adaptive). */
    private fun applyAdapterReasoning(body: RequestBody, state: AnthropicRequestState): Boolean {
        if (claudeGeneration(state.model) == ClaudeGeneration.ADAPTIVE) {
            applyAdaptiveGeneration(body, state)
            return true
        }
        applyManualGeneration(body, state)
        return false
    }

    private fun applyAdaptiveGeneration(body: RequestBody, state: AnthropicRequestState) {
        body.removeAll("temperature", "top_p", "top_k")
        if (!state.thinkingEnabled) {
            if (!claudeThinkingAlwaysOn(state.model)) {
                body.put("thinking", JsonValue.obj("type" to JsonValue.text("disabled")))
                body.remove("effort")
            }
            return
        }
        val thinking = if (state.includeThoughts) {
            JsonValue.obj("type" to JsonValue.text("adaptive"), "display" to JsonValue.text("summarized"))
        } else {
            JsonValue.obj("type" to JsonValue.text("adaptive"))
        }
        body.put("thinking", thinking)
        val effort = state.effort?.let(::mapReasoningEffortToAnthropic)
            ?: state.reasoning.thinkingBudget?.takeIf { it > 0 }?.let { mapBudgetTokensToEffort(it.toLong()) }
        if (effort != null) body.put("effort", effort) else body.remove("effort")
    }

    private fun applyManualGeneration(body: RequestBody, state: AnthropicRequestState) {
        if (state.thinkingEnabled) {
            body.removeAll("temperature", "top_k")
            val topP = (body.get("top_p") as? JsonValue.Number)?.literal?.toDoubleOrNull()
            if (topP != null && (topP < 0.95 || topP > 1.0)) body.put("top_p", number(topP.coerceIn(0.95, 1.0)))
            val maxTokens = firstBodyInteger(body, "max_tokens", "max_completion_tokens")
            val budget = validateAnthropicBudgetTokens(
                state.reasoning.thinkingBudget?.toLong(),
                maxTokens,
                ANTHROPIC_MAX_BUDGET_TOKENS.toLong(),
            )
            body.put(
                "thinking",
                JsonValue.obj(
                    "type" to JsonValue.text("enabled"),
                    "budget_tokens" to JsonValue.integer(budget),
                ),
            )
            return
        }
        if (isClaude45(state.model) && body.has("temperature") && body.has("top_p")) body.remove("top_p")
    }

    /** Frozen base.rs defaultApplyCommonParams for the manual/unsupported generation. */
    private fun applyCommonParams(body: RequestBody, state: AnthropicRequestState) {
        state.parameters.minP?.let { body.put("min_p", number(it)) }
        state.parameters.topK?.let { body.put("top_k", it) }
        state.reasoning.repetitionPenalty?.let { body.put("repetition_penalty", number(it)) }
        state.reasoning.reasoningSplit?.let { body.put("reasoning_split", it) }
        state.parameters.typedExtraBody.forEach { (key, raw) -> body.putTypedExtra(key, raw) }
        state.parameters.extraBody.forEach { (key, raw) -> body.putExtra(key, raw) }
    }

    /** Frozen generation.applyGenerationParams (the official DeepSeek branch cannot match). */
    fun applyGeneration(body: RequestBody, state: AnthropicRequestState) {
        val field = tokenLimitField(state)
        body.put(field, effectiveMaxTokens(state)).remove(otherTokenField(field))
        if (samplingAllowed(state)) applySampling(body, state)
    }

    /** Frozen provider quirks decide the token field; sampling rides reasoning capability. */
    private fun tokenLimitField(state: AnthropicRequestState): String = when {
        isMimoRoute(state) -> "max_completion_tokens"
        isMistralRoute(state) || !state.isReasoning -> "max_tokens"
        else -> "max_completion_tokens"
    }

    private fun otherTokenField(field: String): String = if (field == "max_tokens") "max_completion_tokens" else "max_tokens"

    private fun samplingAllowed(state: AnthropicRequestState): Boolean = !state.isReasoning || isMimoRoute(state)

    private fun applySampling(body: RequestBody, state: AnthropicRequestState) {
        body.put("temperature", number(state.parameters.temperature))
        state.parameters.topP?.let { body.put("top_p", number(it)) }
        state.parameters.frequencyPenalty?.let { body.put("frequency_penalty", number(it)) }
        state.reasoning.presencePenalty?.let { body.put("presence_penalty", number(it)) }
    }

    /** OpenAI-shaped declarations; raw extra tools pass through for the wire conversion. */
    fun applyTools(body: RequestBody, state: AnthropicRequestState) {
        val tools = state.request.tools.map(::openAiTool) + extraRawTools(state)
        if (tools.isEmpty()) return
        body.put("tools", JsonValue.array(tools))
        body.put("tool_choice", "auto")
    }

    /** Frozen `Object.assign(body, options)`: every extra key overrides except reserved ones. */
    fun applyOptions(body: RequestBody, state: AnthropicRequestState) {
        val merged = LinkedHashMap<String, String>()
        state.parameters.extraBody.forEach { (key, value) -> merged[key] = value }
        state.parameters.typedExtraBody.forEach { (key, value) -> merged[key] = value }
        for ((key, raw) in merged) {
            if (key.isBlank() || key in ANTHROPIC_RESERVED_KEYS) continue
            body.put(key, optionValue(raw))
        }
    }

    private fun openAiTool(tool: ProviderTool): JsonValue.Object {
        val function = JsonValue.Object.Builder()
            .put("name", tool.name)
            .put("description", tool.description)
            .put("parameters", runCatching { JsonValue.parseObject(tool.inputSchemaJson) }.getOrElse { JsonValue.Object.EMPTY })
            .putIfNotNull("strict", tool.strict?.let(JsonValue::bool))
        return JsonValue.obj("type" to JsonValue.text("function"), "function" to function.build())
    }

    private fun extraRawTools(state: AnthropicRequestState): List<JsonValue> {
        val raw = state.parameters.typedExtraBody["tools"] ?: state.parameters.extraBody["tools"] ?: return emptyList()
        return (runCatching { JsonValue.parse(raw) }.getOrNull() as? JsonValue.Array)?.values ?: emptyList()
    }

    private fun effectiveMaxTokens(state: AnthropicRequestState): Long {
        val requested = if (state.parameters.maxOutputTokens > 0) state.parameters.maxOutputTokens.toLong() else 8_192L
        val limit = state.resolved.runtime.maxTokensLimit?.takeIf { it > 0 }?.toLong()
        return limit?.let { minOf(requested, it) } ?: requested
    }

    private fun firstBodyInteger(body: RequestBody, vararg keys: String): Long? {
        for (key in keys) {
            val value = (body.get(key) as? JsonValue.Number)?.asLongOrNull()
            if (value != null) return value
        }
        return null
    }

    private fun isMimoRoute(state: AnthropicRequestState): Boolean = state.providerScope == "mimo" ||
        state.providerType == "mimo" || state.modelAdapter == "mimo" ||
        state.baseUrl.lowercase(Locale.ROOT).contains("xiaomimimo.com") ||
        state.model.lowercase(Locale.ROOT).startsWith("mimo-v")

    private fun isMistralRoute(state: AnthropicRequestState): Boolean = state.providerScope == "mistral" ||
        state.providerType == "mistral" || state.modelAdapter == "mistral" ||
        state.baseUrl.lowercase(Locale.ROOT).contains("mistral.ai") || isMistralModel(state.model)

    private fun isMistralModel(model: String): Boolean {
        val slug = model.lowercase(Locale.ROOT).split('/').last()
        return slug.startsWith("mistral-") || slug.startsWith("magistral-")
    }

    private fun optionValue(raw: String): JsonValue = runCatching { JsonValue.parse(raw) }.getOrElse { JsonValue.text(raw) }

    private val ANTHROPIC_RESERVED_KEYS = setOf("model", "messages", "stream", "tools", "tool_choice")
}

/**
 * Intermediate OpenAI-style body messages encoded from the adapter request
 * (`AnthropicRequest.build` stage one). Assistant thinking history rides the
 * sidecar blocks as content parts; unsigned reasoning stays in
 * `reasoning_content` per the frozen deep-seek passback policy. User images
 * come from the ordinary message's [ProviderNativeHistory]-carrying
 * `nativeContent`, with the injected sidecar as the legacy fallback.
 */
internal fun encodeAnthropicChatMessages(
    request: ProviderAdapterRequest,
    sidecar: AnthropicRequestSidecar?,
    targetScope: String,
): JsonValue.Array = JsonValue.array(
    request.messages.mapIndexed { index, message ->
        encodeAnthropicChatMessage(index, message, sidecar, targetScope)
    },
)

private fun encodeAnthropicChatMessage(
    index: Int,
    message: ProviderMessage,
    sidecar: AnthropicRequestSidecar?,
    targetScope: String,
): JsonValue.Object {
    val entries = LinkedHashMap<String, JsonValue>()
    entries["role"] = JsonValue.text(message.role.wireName)
    message.toolCallId?.let { entries["tool_call_id"] = JsonValue.text(it) }
    parsedArray(message.toolCallsJson)?.let { entries["tool_calls"] = JsonValue.array(it) }
    message.reasoningContent?.takeIf { it.isNotEmpty() }?.let { entries["reasoning_content"] = JsonValue.text(it) }
    if (message.role == ProviderRole.ASSISTANT) {
        assistantContent(index, message, sidecar, targetScope)?.let { entries["content"] = it }
    } else {
        userContent(index, message.text, sidecar, message)?.let { entries["content"] = it }
    }
    return JsonValue.Object.from(entries)
}

/**
 * Assistant content is plain text, or replay blocks plus the text as parts.
 * A same-protocol per-message state wins over the sidecar; its own scope is
 * checked so a changed target strips the private parts instead of replaying.
 */
private fun assistantContent(index: Int, message: ProviderMessage, sidecar: AnthropicRequestSidecar?, targetScope: String): JsonValue? {
    val text = message.text
    val replay = nativeReplay(message.nativeContent?.state) ?: sidecarReplay(index, sidecar)
    if (replay == null) return text.takeIf { it.isNotEmpty() }?.let(JsonValue::text)
    val parts = scopedBlocks(replay.blocks, replay.scope, targetScope).toMutableList()
    val hasOrderedText = replay.blocks.any { ResponsesJson.text(ResponsesJson.field(it, "type")) == "text" }
    if (!hasOrderedText && text.isNotEmpty()) parts += JsonValue.obj("type" to JsonValue.text("text"), "text" to JsonValue.text(text))
    return JsonValue.array(parts)
}

/**
 * Ordered blocks of a native state only for this protocol; an empty payload is
 * absent. Only private thinking state replays: tool_use always comes from the
 * caller's paired assistant/tool history, so a native payload can never
 * resurrect an unpaired call.
 */
private fun nativeReplay(state: ProviderNativeHistory?): AnthropicReplayBlocks? {
    if (state == null || state.protocol != ProviderProtocol.ANTHROPIC_MESSAGES) return null
    val blocks = parsedArray(state.payloadJson).orEmpty().filter(::isPrivateReplayBlock)
    return blocks.takeIf { it.isNotEmpty() }?.let { AnthropicReplayBlocks(it, state.stateScope) }
}

private fun isPrivateReplayBlock(block: JsonValue): Boolean = ResponsesJson.text(ResponsesJson.field(block, "type")) in PRIVATE_REPLAY_TYPES

private val PRIVATE_REPLAY_TYPES = setOf("thinking", "redacted_thinking")

/** Legacy sidecar blocks for this message index, if the caller injected them. */
private fun sidecarReplay(index: Int, sidecar: AnthropicRequestSidecar?): AnthropicReplayBlocks? {
    val replay = sidecar?.replay ?: return null
    val blocks = parsedArray(replay.blocksByMessageIndex[index]).orEmpty()
    return blocks.takeIf { it.isNotEmpty() }?.let { AnthropicReplayBlocks(it, replay.scope) }
}

private class AnthropicReplayBlocks(val blocks: List<JsonValue>, val scope: String)

private fun userContent(index: Int, text: String, sidecar: AnthropicRequestSidecar?, message: ProviderMessage): JsonValue? {
    val nativeUrls = message.nativeContent?.imageDataUrls.orEmpty()
    val urls = nativeUrls.takeIf { it.isNotEmpty() } ?: sidecar?.imageUrls?.get(index).orEmpty()
    if (urls.isEmpty()) return text.takeIf { it.isNotEmpty() }?.let(JsonValue::text)
    val parts = mutableListOf<JsonValue>()
    if (text.isNotEmpty()) parts += JsonValue.obj("type" to JsonValue.text("text"), "text" to JsonValue.text(text))
    for (url in urls) {
        parts += JsonValue.obj(
            "type" to JsonValue.text("image_url"),
            "image_url" to JsonValue.obj("url" to JsonValue.text(url)),
        )
    }
    return JsonValue.array(parts)
}

/**
 * Same-target blocks replay verbatim; off-target blocks lose redacted state
 * and signatures, keeping thinking text visible as a plain text part and
 * regular content (text/tool_use) in place — the frozen
 * sanitizeHistoryReasoningForTarget direction.
 */
private fun scopedBlocks(blocks: List<JsonValue>, savedScope: String, targetScope: String): List<JsonValue> {
    if (AnthropicHistoryScope.canReplay(savedScope, targetScope)) return blocks
    return blocks.mapNotNull { block ->
        val obj = ResponsesJson.obj(block) ?: return@mapNotNull block
        when (ResponsesJson.text(obj["type"])) {
            "thinking" -> JsonValue.obj(
                "type" to JsonValue.text("text"),
                "text" to JsonValue.text(ResponsesJson.text(obj["thinking"]).orEmpty()),
            )

            "redacted_thinking" -> null

            else -> stripPrivateKeys(obj)
        }
    }
}

/** Signature-like private keys drop from every kept off-target block. */
private fun stripPrivateKeys(block: JsonValue.Object): JsonValue.Object = JsonValue.Object.from(
    LinkedHashMap<String, JsonValue>().apply {
        for ((key, value) in block.asMap()) if (key !in PRIVATE_KEYS) put(key, value)
    },
)

private val PRIVATE_KEYS = setOf(
    "providerState", "stateScope", "signature", "thinking_signature", "thought_signature",
    "thoughtSignature", "encrypted_content", "extra_content", "reasoning_details", "redacted_thinking",
)

private fun parsedArray(raw: String?): List<JsonValue>? =
    raw?.let { value -> runCatching { JsonValue.parse(value) }.getOrNull() as? JsonValue.Array }?.values
