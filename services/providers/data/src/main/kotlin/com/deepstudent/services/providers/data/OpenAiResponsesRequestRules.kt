package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import java.util.Locale

/**
 * Request-adapter state for the Responses pipeline; mirrors the frozen ApiConfig
 * fields the request path reads (`host/plugins/llm/src/adapters` directory).
 */
internal class ResponsesRequestState(val request: ProviderAdapterRequest) {
    val resolved get() = request.resolved
    val model: String get() = resolved.profile.model
    val baseUrl: String get() = resolved.baseUrl
    val providerType: String get() = resolved.providerType.trim().lowercase(Locale.ROOT)
    val providerScope: String get() = resolved.providerScope.trim().lowercase(Locale.ROOT)
    val modelAdapter: String get() = resolved.profile.adapter.wireName.lowercase(Locale.ROOT)

    /** Frozen requestAdapterIdForConfig result, resolved by the backend route step. */
    val adapterId: String get() = resolved.adapterId.trim().lowercase(Locale.ROOT)
    val sessionId: String? get() = request.sessionId
    val parameters get() = resolved.runtime.parameters
    val reasoning get() = resolved.runtime.reasoning
    val isReasoning: Boolean get() = resolved.runtime.isReasoning
    val supportsReasoning: Boolean get() = resolved.runtime.features.reasoning

    /** Frozen resolveEnableThinking folds enableThinking/thinkingEnabled into one runtime flag. */
    val thinkingEnabled: Boolean get() = resolved.runtime.thinkingEnabled
    val effort: String? get() = reasoning.reasoningEffort?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)

    fun isSiliconflow(): Boolean = providerType == "siliconflow" || providerScope == "siliconflow" ||
        baseUrl.lowercase(Locale.ROOT).let { it.contains("siliconflow.cn") || it.contains("siliconflow.com") }

    fun isOpenrouter(): Boolean = providerType == "openrouter" || providerScope == "openrouter" ||
        ResponsesRequestPayload.hostOf(baseUrl)?.let { it == "openrouter.ai" || it.endsWith(".openrouter.ai") } == true
}

/** One frozen request adapter (`host/plugins/llm/src/adapters/{base,generic_openai,grok}.ts`). */
internal interface ResponsesRequestAdapter {
    fun shouldRemoveSampling(state: ResponsesRequestState): Boolean

    /** Returns true when common params must be skipped (frozen early return). */
    fun applyReasoning(body: RequestBody, state: ResponsesRequestState): Boolean

    fun applyCommon(body: RequestBody, state: ResponsesRequestState)
}

internal object ResponsesRequestRules {
    /**
     * Frozen question_authoring.applyReasoningConfig: first strip sampling per
     * adapter, then reason, then common params. The reasoning level map is
     * identity here: Android keeps no reasoning-level registry, and every
     * frozen oracle effort passes through unchanged.
     */
    fun applyReasoning(body: RequestBody, state: ResponsesRequestState) {
        val adapter = adapterFor(state)
        if (adapter.shouldRemoveSampling(state)) {
            body.removeAll("temperature", "top_p", "presence_penalty", "frequency_penalty", "logprobs")
        }
        if (adapter.applyReasoning(body, state)) return
        adapter.applyCommon(body, state)
    }

    /** Frozen generation.applyGenerationParams plus the official DeepSeek early branch. */
    fun applyGeneration(body: RequestBody, state: ResponsesRequestState) {
        if (DeepseekResponsesRules.applyOfficialGeneration(body, state)) return
        val quirks = quirks(state)
        applyGenerationTokenLimit(body, quirks, effectiveMaxTokens(state))
        if (quirks.samplingAllowed) {
            body.put("temperature", number(state.parameters.temperature))
            state.parameters.topP?.let { body.put("top_p", number(it)) }
            state.parameters.frequencyPenalty?.let { body.put("frequency_penalty", number(it)) }
            state.reasoning.presencePenalty?.let { body.put("presence_penalty", number(it)) }
        }
    }

    private fun adapterFor(state: ResponsesRequestState): ResponsesRequestAdapter = when (state.adapterId) {
        "deepseek" -> DeepseekResponsesRules
        "grok" -> GrokResponsesRules
        else -> GenericResponsesRules
    }

    private fun effectiveMaxTokens(state: ResponsesRequestState): Long {
        val requested = if (state.parameters.maxOutputTokens > 0) state.parameters.maxOutputTokens.toLong() else 8_192L
        val limit = state.resolved.runtime.maxTokensLimit?.takeIf { it > 0 }?.toLong()
        return limit?.let { minOf(requested, it) } ?: requested
    }

    private fun applyGenerationTokenLimit(body: RequestBody, quirks: ResponsesQuirks, maxTokens: Long) {
        if (quirks.maxTokensField == "max_completion_tokens" && quirks.preserveExistingMaxTokens) {
            body.put("max_completion_tokens", maxTokens)
            return
        }
        if (quirks.maxTokensField == "max_completion_tokens") {
            body.put("max_completion_tokens", maxTokens).remove("max_tokens")
        } else {
            body.put("max_tokens", maxTokens).remove("max_completion_tokens")
        }
    }

    private data class ResponsesQuirks(val maxTokensField: String, val preserveExistingMaxTokens: Boolean, val samplingAllowed: Boolean)

    /** Frozen provider_quirks.resolveQuirks fields the request path consumes. */
    private fun quirks(state: ResponsesRequestState): ResponsesQuirks {
        val isMimo = isMimoRoute(state)
        val isMistral = isMistralRoute(state)
        val maxTokensField = when {
            isMimo -> "max_completion_tokens"
            isMistral -> "max_tokens"
            state.isReasoning -> "max_completion_tokens"
            else -> "max_tokens"
        }
        return ResponsesQuirks(maxTokensField, state.isReasoning && !isMimo && !isMistral, !state.isReasoning || isMimo)
    }

    private fun isMimoRoute(state: ResponsesRequestState): Boolean = state.providerScope == "mimo" ||
        state.providerType == "mimo" ||
        state.modelAdapter == "mimo" ||
        state.baseUrl.lowercase(Locale.ROOT).contains("xiaomimimo.com") ||
        state.model.lowercase(Locale.ROOT).startsWith("mimo-v")

    private fun isMistralRoute(state: ResponsesRequestState): Boolean = state.providerScope == "mistral" ||
        state.providerType == "mistral" ||
        state.modelAdapter == "mistral" ||
        state.baseUrl.lowercase(Locale.ROOT).contains("mistral.ai") ||
        isMistralModel(state.model)

    private fun isMistralModel(model: String): Boolean {
        val slug = model.lowercase(Locale.ROOT).split('/').last()
        return slug.startsWith("mistral-") || slug.startsWith("magistral-")
    }

    internal fun mergeExtraBody(body: RequestBody, state: ResponsesRequestState) {
        state.parameters.typedExtraBody.forEach { (key, raw) -> body.putTypedExtra(key, raw) }
        state.parameters.extraBody.forEach { (key, raw) -> body.putExtra(key, raw) }
    }
}

/** Frozen generic_openai.rs adapter (generic_openai.ts GenericOpenAIAdapter). */
internal object GenericResponsesRules : ResponsesRequestAdapter {
    override fun shouldRemoveSampling(state: ResponsesRequestState): Boolean =
        !state.isSiliconflow() && shouldRemoveSamplingForReasoning(state)

    override fun applyReasoning(body: RequestBody, state: ResponsesRequestState): Boolean {
        if (state.isSiliconflow() && (state.supportsReasoning || state.isReasoning)) {
            siliconFlowReasoning(body, state)
            return false
        }
        if (shouldRemoveSamplingForReasoning(state)) body.removeAll("temperature", "top_p", "logprobs")
        val hasResponsesReasoningMode = state.model.trim().lowercase(Locale.ROOT).startsWith("gpt-5.6") &&
            responsesReasoningMode(state) != null
        body.removeAll("enable_thinking", "thinking_budget", "include_thoughts", "thinking", "output_config")
        val earlyReturn = applyEffort(body, state)
        if (hasResponsesReasoningMode) applyResponsesReasoningMode(body, state)
        return earlyReturn
    }

    private fun applyEffort(body: RequestBody, state: ResponsesRequestState): Boolean {
        val effort = requestedEffort(state)
        if (effort != null) return applyExplicitEffort(body, state, effort)
        if (!(state.supportsReasoning || state.isReasoning)) return false
        val normalized = if (state.thinkingEnabled) {
            state.reasoning.thinkingBudget?.let(::effortFromBudget) ?: "medium"
        } else {
            "none"
        }
        insertReasoningEffort(body, state, normalized)
        return false
    }

    private fun responsesReasoningMode(state: ResponsesRequestState): String? =
        state.reasoning.reasoningMode?.trim()?.lowercase(Locale.ROOT)?.takeIf { it == "standard" || it == "pro" }

    override fun applyCommon(body: RequestBody, state: ResponsesRequestState) {
        state.parameters.minP?.let { body.put("min_p", number(it)) }
        state.parameters.topK?.let { body.put("top_k", it) }
        state.reasoning.repetitionPenalty?.let { body.put("repetition_penalty", number(it)) }
        state.reasoning.reasoningSplit?.let { body.put("reasoning_split", it) }
        body.remove("effort")
        ResponsesRequestRules.mergeExtraBody(body, state)
    }

    private fun siliconFlowReasoning(body: RequestBody, state: ResponsesRequestState) {
        body.put("enable_thinking", state.thinkingEnabled)
        body.removeAll("reasoning", "thinking")
        if (!state.thinkingEnabled) {
            body.removeAll("thinking_budget", "reasoning_effort")
            return
        }
        val normalized = state.effort?.let(::normalizeSiliconFlowEffort)
        if (normalized != null) {
            body.put("reasoning_effort", normalized).remove("thinking_budget")
        } else {
            body.remove("reasoning_effort")
            val budget = state.reasoning.thinkingBudget
            if (budget != null) body.put("thinking_budget", budget.coerceIn(128, 32_768)) else body.remove("thinking_budget")
        }
    }

    private fun requestedEffort(state: ResponsesRequestState): String? = state.effort

    private fun applyExplicitEffort(body: RequestBody, state: ResponsesRequestState, effort: String): Boolean {
        val requested = effort.lowercase(Locale.ROOT)
        if (requested == "unset") {
            body.removeAll("reasoning_effort", "reasoning")
            return true
        }
        val normalized = normalizeEffortForModel(state, effort)
        if (normalized == null) {
            body.removeAll("reasoning_effort", "reasoning")
            return false
        }
        if (normalized == "none") {
            insertReasoningEffort(body, state, "none")
            return true
        }
        insertReasoningEffort(body, state, normalized)
        return false
    }

    private fun applyResponsesReasoningMode(body: RequestBody, state: ResponsesRequestState) {
        val normalized = responsesReasoningMode(state) ?: return
        val existing = body.get("reasoning") as? JsonValue.Object
        body.put(
            "reasoning",
            if (existing == null) {
                JsonValue.obj("mode" to JsonValue.text(normalized))
            } else {
                JsonValue.Object.from(LinkedHashMap(existing.asMap()).apply { put("mode", JsonValue.text(normalized)) })
            },
        )
    }

    private fun insertReasoningEffort(body: RequestBody, state: ResponsesRequestState, effort: String) {
        if (state.isOpenrouter()) {
            body.remove("reasoning_effort")
            val existing = body.get("reasoning") as? JsonValue.Object
            body.put(
                "reasoning",
                if (existing == null) {
                    JsonValue.obj("effort" to JsonValue.text(effort))
                } else {
                    JsonValue.Object.from(
                        LinkedHashMap(existing.asMap()).apply {
                            remove("max_tokens")
                            remove("enabled")
                            put("effort", JsonValue.text(effort))
                        },
                    )
                },
            )
        } else {
            body.put("reasoning_effort", effort).remove("reasoning")
        }
    }

    private fun shouldRemoveSamplingForReasoning(state: ResponsesRequestState): Boolean {
        val effort = requestedEffort(state)
        if (effort != null) return effort in VALID_EFFORTS && normalizeStandardEffort(effort) != "none"
        if (!(state.isReasoning || state.supportsReasoning) || !state.thinkingEnabled) return false
        val budget = state.reasoning.thinkingBudget ?: return true
        return effortFromBudget(budget) != "none"
    }

    private fun normalizeEffortForModel(state: ResponsesRequestState, effort: String): String? {
        if (effort.trim().lowercase(Locale.ROOT) == "max" && (isMaxCapableGptFamily(state) || isRelayHost(state))) return "max"
        return normalizeStandardEffort(effort)
    }

    private fun isMaxCapableGptFamily(state: ResponsesRequestState): Boolean {
        val segment = state.model.trim().lowercase(Locale.ROOT).split('/').last()
        val rest = when {
            segment.startsWith("gpt-5.6") -> segment.substring("gpt-5.6".length)
            segment.startsWith("gpt-6") -> segment.substring("gpt-6".length)
            else -> return false
        }
        return rest.isEmpty() || rest.first() in "._-"
    }

    private fun isRelayHost(state: ResponsesRequestState): Boolean {
        if (state.isSiliconflow() || state.isOpenrouter()) return false
        return state.providerType in RELAY_HOSTS
    }
}

/** Frozen grok.rs adapter (grok.ts GrokAdapter). */
internal object GrokResponsesRules : ResponsesRequestAdapter {
    override fun shouldRemoveSampling(state: ResponsesRequestState): Boolean = false

    override fun applyReasoning(body: RequestBody, state: ResponsesRequestState): Boolean {
        // The pipeline passes enableThinking = null, so the override branch never fires.
        if (supportsReasoningEffort(state.model)) {
            val requested = state.effort
            if (requested != null) body.put("reasoning_effort", normalizedEffort(state.model, requested))
        }
        if (isGrok4(state.model)) {
            body.removeAll("presence_penalty", "frequency_penalty", "presencePenalty", "frequencyPenalty", "stop")
        }
        return false
    }

    override fun applyCommon(body: RequestBody, state: ResponsesRequestState) {
        ResponsesRequestRules.mergeExtraBody(body, state)
    }

    private fun normalizedEffort(model: String, requested: String): String {
        val requires = requiresReasoning(model)
        return when (requested.lowercase(Locale.ROOT)) {
            "none" -> if (requires) "low" else "none"
            "minimal", "low" -> "low"
            "medium" -> "medium"
            "xhigh" -> if (supportsXhigh(model)) "xhigh" else "high"
            "high", "max" -> "high"
            else -> "low"
        }
    }

    private fun supportsXhigh(model: String): Boolean = isGrok420MultiAgent(model) || supportsXhighVersion(model)

    private fun supportsXhighVersion(model: String): Boolean {
        val version = parseGrokVersion(model) ?: return false
        return version.first > 4 || (version.first == 4 && version.second >= 6)
    }

    internal fun parseGrokVersion(model: String): Pair<Int, Int>? {
        val lower = model.lowercase(Locale.ROOT)
        val index = lower.indexOf("grok")
        if (index == -1) return null
        var rest = lower.substring(index + 4)
        if (rest.startsWith("-")) rest = rest.substring(1)
        val majorMatch = Regex("^\\d+").find(rest) ?: return null
        val major = majorMatch.value.toIntOrNull() ?: return null
        var minor = 0
        val afterMajor = rest.substring(majorMatch.value.length)
        if (afterMajor.startsWith(".") || afterMajor.startsWith("-")) {
            Regex("^\\d{1,2}").find(afterMajor.substring(1))?.let { minor = it.value.toIntOrNull() ?: 0 }
        }
        return major to minor
    }

    internal fun supportsReasoningEffort(model: String): Boolean {
        val lower = model.lowercase(Locale.ROOT)
        if (lower.contains("non-reasoning")) return false
        if (lower == "grok-latest" || lower.endsWith("/grok-latest")) return true
        val version = parseGrokVersion(model) ?: return false
        return version.first > 4 || (version.first == 4 && version.second >= 3)
    }

    private fun requiresReasoning(model: String): Boolean {
        val lower = model.lowercase(Locale.ROOT)
        if (lower == "grok-latest" || lower.endsWith("/grok-latest")) return true
        val version = parseGrokVersion(model) ?: return false
        return (version.first > 4 || (version.first == 4 && version.second >= 5)) && !lower.contains("non-reasoning")
    }

    private fun isGrok420MultiAgent(model: String): Boolean {
        val version = parseGrokVersion(model) ?: return false
        return version.first == 4 && version.second == 20 && model.lowercase(Locale.ROOT).contains("multi-agent")
    }

    private fun isGrok4(model: String): Boolean {
        val lower = model.lowercase(Locale.ROOT)
        return lower.contains("grok-4") || lower.contains("grok4")
    }
}

private val RELAY_HOSTS = setOf("custom", "general", "one-api", "one_api", "sub2api", "cpa", "together", "fireworks", "groq")
