package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderTool
import com.deepstudent.services.providers.api.ReasoningParameters
import com.deepstudent.services.providers.api.ResolvedProviderConfig

/**
 * S3.07 Google/Gemini intermediate request composition. Mirrors the frozen desktop
 * chain for the `google_generate_content` dialect: model2_request body assembly, the
 * Gemini request-adapter thinking map (3.x levels vs 2.5 budgets), the official
 * Gemini effort level registry, then generation parameters
 * (host/plugins/llm/src/{model2_request,generation,reasoning_level_map,
 * provider_quirks,adapters/gemini}.ts). The compiled messages come from the caller;
 * context compilation stays with S3.12.
 */
internal class GoogleGeminiRequest(
    private val request: ProviderAdapterRequest,
    private val scope: GoogleGeminiScope,
    private val signatures: GeminiThoughtSignatureSource?,
    private val images: GeminiImageContentSource?,
) {
    fun body(): JsonValue.Object {
        val state = State(request.resolved, request)
        val body = RequestBody(linkedMapOf())
        body.put("model", state.model)
        body.put("messages", messages())
        body.put("stream", true)
        applyReasoningConfig(body, state)
        applyTools(body, state)
        applyGenerationParams(body, state)
        return body.build()
    }

    private fun messages(): JsonValue.Array = JsonValue.array(request.messages.mapIndexed(::messageJson))

    private fun messageJson(index: Int, message: ProviderMessage): JsonValue.Object {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["role"] = JsonValue.text(message.role.wireName)
        fields["content"] = content(index, message)
        message.name?.takeIf { it.isNotEmpty() }?.let { fields["name"] = JsonValue.text(it) }
        message.toolCallsJson?.takeIf { message.role == ProviderRole.ASSISTANT }?.let { raw ->
            fields["tool_calls"] = parsedJson(raw)
        }
        message.toolCallId?.let { fields["tool_call_id"] = JsonValue.text(it) }
        if (message.role == ProviderRole.ASSISTANT) {
            scope.replayable(signatureFor(index, message))?.let {
                fields["thought_signature"] = JsonValue.text(it.signature)
            }
        }
        return JsonValue.Object.from(fields)
    }

    /**
     * A same-target per-message native state wins; the legacy injected port
     * keeps supplying unscoped oracle history as before.
     */
    private fun signatureFor(index: Int, message: ProviderMessage): GeminiSignedTurn? =
        message.nativeContent?.state?.geminiSignedTurn(scope.value) ?: signatures?.signatureFor(index)

    /** User images arrive preprocessed; the port never downloads remote URLs. */
    private fun content(index: Int, message: ProviderMessage): JsonValue {
        val imageParts = if (message.role == ProviderRole.USER) userImageParts(index, message) else emptyList()
        if (imageParts.isEmpty()) return JsonValue.text(message.text)
        val parts = mutableListOf<JsonValue>()
        if (message.text.isNotEmpty()) {
            parts += JsonValue.obj("type" to JsonValue.text("text"), "text" to JsonValue.text(message.text))
        }
        imageParts.forEach { parts += it.dataUrlPart() }
        return JsonValue.array(parts)
    }

    /**
     * Native preprocessed data URLs ride the ordinary message; the legacy
     * image port is only consulted when the message carries no images.
     */
    private fun userImageParts(index: Int, message: ProviderMessage): List<GeminiImagePart> {
        val native = message.nativeContent?.imageDataUrls.orEmpty()
        if (native.isNotEmpty()) return native.map { GeminiImagePart.DataUrl(it) }
        return images?.imagesFor(index).orEmpty()
    }

    private fun GeminiImagePart.dataUrlPart(): JsonValue = JsonValue.obj(
        "type" to JsonValue.text("image_url"),
        "image_url" to JsonValue.obj(
            "url" to JsonValue.text(
                when (this) {
                    is GeminiImagePart.DataUrl -> url
                    is GeminiImagePart.Inline -> "data:$mimeType;base64,$base64Data"
                },
            ),
        ),
    )

    private fun parsedJson(raw: String): JsonValue = runCatching { JsonValue.parse(raw) }
        .getOrElse { JsonValue.array(emptyList()) }

    /** Frozen Gemini request adapter (`llm/src/adapters/gemini.ts` applyReasoningConfig). */
    private fun applyReasoningConfig(body: RequestBody, state: State) {
        val thinking = JsonValue.Object.Builder()
        if (state.isGemini3) {
            thinking.put("thinkingLevel", thinkingLevel(state))
        } else {
            thinkingBudget(state)?.let { thinking.put("thinkingBudget", it) }
        }
        if (state.reasoning.includeThoughts || state.enableThinking) thinking.put("includeThoughts", true)
        val config = thinking.build()
        if (!config.isEmpty()) body.put("thinkingConfig", config)
        val apiVersion = state.parameters.extraBody["gemini_api_version"] ?: DEFAULT_GEMINI_API_VERSION
        if (apiVersion.isNotEmpty()) body.put("gemini_api_version", apiVersion)
        applyCommonParams(body, state)
    }

    private fun thinkingLevel(state: State): String = when {
        state.enableThinking -> mapEffortToLevel(state.mappedEffort, state.model, state.isGemini3Flash)
        state.isGemini3Flash -> "minimal"
        else -> "low"
    }

    private fun thinkingBudget(state: State): Long? = when {
        state.enableThinking -> state.reasoning.thinkingBudget?.let { clampGemini25Budget(state.model, it.toLong()) } ?: -1L
        state.model.geminiAsciiLower().contains("flash") -> 0L
        else -> null
    }

    private fun applyCommonParams(body: RequestBody, state: State) {
        state.parameters.minP?.let { body.put("min_p", number(it)) }
        state.parameters.topK?.let { body.put("top_k", JsonValue.integer(it.toLong())) }
        state.reasoning.repetitionPenalty?.let { body.put("repetition_penalty", number(it)) }
        state.reasoning.reasoningSplit?.let { body.put("reasoning_split", JsonValue.bool(it)) }
        mergeExtraBody(body, state)
    }

    /** Frozen `mergeExtraBody`: existing keys win; empty keys are skipped. */
    private fun mergeExtraBody(body: RequestBody, state: State) {
        state.parameters.typedExtraBody.forEach { (key, raw) ->
            if (key.isNotEmpty() && !body.has(key)) body.put(key, parsedExtra(raw))
        }
        state.parameters.extraBody.forEach { (key, raw) ->
            if (key.isNotEmpty() && !body.has(key)) body.put(key, parsedExtra(raw))
        }
    }

    private fun parsedExtra(raw: String): JsonValue = runCatching { JsonValue.parse(raw.trim()) }
        .getOrElse { JsonValue.text(raw) }

    private fun applyTools(body: RequestBody, state: State) {
        if (!state.toolsEnabled) return
        val tools = JsonValue.array(request.tools.filter { it.name.isNotBlank() }.map(::toolJson))
        if (tools.values.isEmpty()) return
        body.put("tools", tools)
        body.put("tool_choice", JsonValue.text("auto"))
    }

    private fun toolJson(tool: ProviderTool): JsonValue = JsonValue.obj(
        "type" to JsonValue.text("function"),
        "function" to JsonValue.obj(
            "name" to JsonValue.text(tool.name),
            "description" to JsonValue.text(tool.description),
            "parameters" to runCatching { JsonValue.parse(tool.inputSchemaJson) }.getOrElse { JsonValue.Object.EMPTY },
        ),
    )

    /** Google is neither MiMo nor Mistral: reasoning models keep max_completion_tokens. */
    private fun applyGenerationParams(body: RequestBody, state: State) {
        if (state.isReasoning) {
            body.put("max_completion_tokens", state.maxTokens)
        } else {
            body.put("max_tokens", state.maxTokens).remove("max_completion_tokens")
        }
        if (!state.isReasoning) {
            body.put("temperature", geminiF32ShortestNumber(state.parameters.temperature))
            state.parameters.topP?.let { body.put("top_p", geminiF32ShortestNumber(it)) }
            state.parameters.frequencyPenalty?.let { body.put("frequency_penalty", geminiF32ShortestNumber(it)) }
            state.reasoning.presencePenalty?.let { body.put("presence_penalty", geminiF32ShortestNumber(it)) }
        }
    }

    private class State(val resolved: ResolvedProviderConfig, val request: ProviderAdapterRequest) {
        val model: String get() = resolved.profile.model
        val parameters get() = resolved.runtime.parameters
        val reasoning: ReasoningParameters get() = resolved.runtime.reasoning
        val isReasoning: Boolean get() = resolved.runtime.isReasoning
        val enableThinking: Boolean get() = resolved.runtime.thinkingEnabled
        private val lowerModel get() = model.geminiAsciiLower()
        val isGemini3: Boolean get() = lowerModel.contains("gemini-3") || lowerModel.contains("gemini3")
        val isGemini3Flash: Boolean get() = isGemini3 && lowerModel.contains("flash")
        val mappedEffort: String? get() = mapReasoningLevel(resolved) ?: reasoning.reasoningEffort
        val toolsEnabled: Boolean get() = resolved.runtime.capabilities.tools && request.tools.isNotEmpty()
        val maxTokens: Long
            get() {
                val requested = parameters.maxOutputTokens.toLong().takeIf { it > 0 } ?: 8_192L
                val limit = resolved.runtime.maxTokensLimit?.toLong()?.takeIf { it > 0 }
                return limit?.let { minOf(requested, it) } ?: requested
            }
    }
}

/** Frozen `f32_to_json_number`: shortest f32 decimal, integers without a trailing zero. */
internal fun geminiF32ShortestNumber(value: Double): JsonValue.Number {
    val shortest = geminiF32Shortest(value)
    val text = if (shortest == Math.rint(shortest) && kotlin.math.abs(shortest) < 1e15) {
        shortest.toLong().toString()
    } else {
        shortest.toString()
    }
    return JsonValue.Number.raw(text)
}

/** Frozen `f32_shortest`: shortest decimal that survives an f32 round trip. */
internal fun geminiF32Shortest(value: Double): Double {
    val target = value.toFloat()
    for (precision in 1..9) {
        val candidate = java.math.BigDecimal(target.toDouble(), java.math.MathContext(precision)).toDouble()
        if (candidate.toFloat() == target) return candidate
    }
    return target.toDouble()
}

private fun clampGemini25Budget(model: String, budget: Long): Long = when {
    budget <= -1L -> -1L
    model.geminiAsciiLower().contains("flash") -> minOf(24_576L, maxOf(0L, budget))
    else -> minOf(32_768L, maxOf(128L, budget))
}

/** Frozen `map_effort_to_level` (`llm/src/adapters/gemini.ts`); `flash` is the Gemini 3 flash flag. */
internal fun mapEffortToLevel(effort: String?, model: String, gemini3Flash: Boolean): String = when (effort?.geminiAsciiLower()) {
    "high", "xhigh" -> "high"
    "low" -> "low"
    "medium" -> if (gemini3Flash) "medium" else "high"
    "minimal", "none" -> if (gemini3Flash) "minimal" else "low"
    else -> defaultThinkingLevel(model, gemini3Flash)
}

private fun defaultThinkingLevel(model: String, flash: Boolean): String {
    val lower = model.geminiAsciiLower()
    return when {
        lower.contains("flash-lite") -> "minimal"
        lower.contains("gemini-3.5-flash") -> "medium"
        lower.contains("gemini-3-flash") -> "high"
        flash -> "low"
        else -> "high"
    }
}

/** Official Gemini effort level profiles (frozen reasoning_level_registry.ts, gemini family). */
private class GeminiLevelProfile(
    val id: String,
    val modelContains: List<String>,
    val levels: List<String>,
    val aliases: Map<String, String>,
)

private val GEMINI_LEVEL_PROFILES = listOf(
    GeminiLevelProfile(
        "gemini-3-pro",
        listOf("gemini-3", "pro"),
        listOf("low", "medium", "high"),
        mapOf("minimal" to "low", "xhigh" to "high", "max" to "high"),
    ),
    GeminiLevelProfile(
        "gemini-3-flash-lite",
        listOf("gemini-3", "flash-lite"),
        listOf("minimal", "low", "medium", "high"),
        emptyMap(),
    ),
    GeminiLevelProfile("gemini-3.8-flash", listOf("gemini-3.8-flash"), listOf("low", "medium", "high"), emptyMap()),
    GeminiLevelProfile("gemini-3.7-flash", listOf("gemini-3.7-flash"), listOf("low", "medium", "high"), emptyMap()),
    GeminiLevelProfile("gemini-3-flash", listOf("gemini-3", "flash"), listOf("minimal", "low", "medium", "high"), emptyMap()),
)

private val LEVEL_RANKS = mapOf(
    "none" to 0,
    "minimal" to 1,
    "low" to 2,
    "medium" to 3,
    "high" to 4,
    "xhigh" to 5,
    "max" to 6,
)

/**
 * Frozen `map_reasoning_level_for_config` restricted to the Gemini family: only
 * `reasoningEffort` is rewritten, never budgets, thinking switches or sampling.
 * google/gemini providers are official and have no protocol ceiling entry.
 */
internal fun mapReasoningLevel(config: ResolvedProviderConfig): String? {
    val requested = config.runtime.reasoning.reasoningEffort?.trim() ?: return null
    val lower = requested.geminiAsciiLower()
    if (lower == "none") return null
    val rank = LEVEL_RANKS[lower] ?: return null
    val profile = findGeminiLevelProfile(config.profile.model.trim().geminiAsciiLower()) ?: return null
    val allowed = profile.levels
    profile.aliases[lower]?.takeIf { it != lower && it in allowed }?.let { return it }
    if (lower in allowed) return null
    return snapToLevel(rank, allowed)
}

private fun findGeminiLevelProfile(model: String): GeminiLevelProfile? {
    var best: GeminiLevelProfile? = null
    var bestLength = -1
    for (profile in GEMINI_LEVEL_PROFILES) {
        val matches = profile.modelContains.all { containsAtVersionBoundary(model, it.geminiAsciiLower()) }
        if (!matches) continue
        val length = profile.modelContains.sumOf(String::length)
        if (length > bestLength) {
            best = profile
            bestLength = length
        }
    }
    return best
}

/** Frozen `contains_at_version_boundary`: a trailing digit marks another model version. */
private fun containsAtVersionBoundary(model: String, fragment: String): Boolean {
    var index = model.indexOf(fragment)
    while (index != -1) {
        if (!model.substring(index + fragment.length).matches(Regex("\\d.*"))) return true
        index = model.indexOf(fragment, index + 1)
    }
    return false
}

/** Equal-distance ties snap to the higher level, as in the frozen registry mapping. */
private fun snapToLevel(requestedRank: Int, allowed: List<String>): String? {
    var best: Pair<Int, String>? = null
    for (candidate in allowed) {
        val rank = LEVEL_RANKS[candidate] ?: continue
        val distance = kotlin.math.abs(rank - requestedRank)
        val current = best
        val better = current == null || distance < current.first ||
            (distance == current.first && LEVEL_RANKS[current.second]!! < rank)
        if (better) best = distance to candidate
    }
    return best?.second
}

internal fun String.geminiAsciiLower(): String = buildString {
    for (char in this@geminiAsciiLower) append(if (char in 'A'..'Z') char.lowercaseChar() else char)
}

private const val DEFAULT_GEMINI_API_VERSION = "v1"
