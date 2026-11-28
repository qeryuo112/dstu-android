package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import java.util.Locale

/**
 * Frozen DeepSeek request adapter (`host/plugins/llm/src/adapters/deepseek.ts`
 * DeepSeekAdapter plus the official V4 generation/sampling/passback helpers).
 *
 * The Android runtime folds frozen enableThinking/thinkingEnabled into one
 * flag; the raw [com.deepstudent.services.providers.api.ReasoningFlags] value
 * stands in for frozen config.enableThinking, and only an explicit false
 * disables a V4 model's native thinking.
 */
internal object DeepseekResponsesRules : ResponsesRequestAdapter {
    private enum class ModelVersion { V31, V32, V4, LEGACY_ALIAS, UNKNOWN }

    private enum class HostProtocol { OFFICIAL, SILICONFLOW, OTHER }

    override fun shouldRemoveSampling(state: ResponsesRequestState): Boolean =
        !isOfficialV4(state) && resolveThinking(state, classifyModel(state.model)) && isV4EffortCapable(classifyModel(state.model))

    override fun applyReasoning(body: RequestBody, state: ResponsesRequestState): Boolean {
        val version = classifyModel(state.model)
        val host = hostProtocol(state)
        if (version == ModelVersion.V31 && (body.has("tools") || body.has("tool_choice"))) {
            body.removeAll("enable_thinking", "thinking", "thinking_budget", "include_thoughts", "reasoning_effort")
            return false
        }
        if (state.supportsReasoning || version in CAPABLE_VERSIONS) {
            val enableThinking = resolveThinking(state, version)
            if (host == HostProtocol.SILICONFLOW) {
                applySiliconflowReasoning(body, state, version, enableThinking)
            } else {
                applyOfficialStyleReasoning(body, state, version, host, enableThinking)
            }
        }
        if (isOfficialV4(state)) applyOfficialSampling(body, resolveThinking(state, version))
        return false
    }

    override fun applyCommon(body: RequestBody, state: ResponsesRequestState) {
        state.parameters.minP?.let { body.put("min_p", number(it)) }
        state.parameters.topK?.let { body.put("top_k", it) }
        state.reasoning.repetitionPenalty?.let { body.put("repetition_penalty", number(it)) }
        state.reasoning.reasoningSplit?.let { body.put("reasoning_split", it) }
        body.remove("effort")
        ResponsesRequestRules.mergeExtraBody(body, state)
    }

    private fun applySiliconflowReasoning(body: RequestBody, state: ResponsesRequestState, version: ModelVersion, enableThinking: Boolean) {
        body.put("enable_thinking", enableThinking)
        body.remove("thinking")
        if (!isV4EffortCapable(version)) {
            body.remove("reasoning_effort")
            resolveSiliconflowBudget(state, version)?.let { body.put("thinking_budget", it.coerceIn(128, 32_768)) }
            return
        }
        body.remove("thinking_budget")
        val effort = shouldApplyReasoningEffort(state, version, HostProtocol.SILICONFLOW, enableThinking)
        if (effort != null) body.put("reasoning_effort", effort) else body.remove("reasoning_effort")
    }

    private fun applyOfficialStyleReasoning(
        body: RequestBody,
        state: ResponsesRequestState,
        version: ModelVersion,
        host: HostProtocol,
        enableThinking: Boolean,
    ) {
        body.put("thinking", JsonValue.obj("type" to JsonValue.text(if (enableThinking) "enabled" else "disabled")))
        body.removeAll("enable_thinking", "thinking_budget")
        val effort = shouldApplyReasoningEffort(state, version, host, enableThinking)
        if (effort != null) body.put("reasoning_effort", effort) else body.remove("reasoning_effort")
    }

    /** Frozen generation.applyOfficialDeepseekGenerationParams early branch. */
    fun applyOfficialGeneration(body: RequestBody, state: ResponsesRequestState): Boolean {
        if (!isOfficialV4(state)) return false
        val thinkingType = (body.get("thinking") as? JsonValue.Object)?.get("type")?.let(ResponsesJson::text)
        val thinking = thinkingType != "disabled"
        val requested = if (state.parameters.maxOutputTokens >
            0
        ) {
            state.parameters.maxOutputTokens.toLong()
        } else {
            defaultTokens(body, thinking)
        }
        val limit = state.resolved.runtime.maxTokensLimit?.takeIf { it > 0 }?.toLong() ?: 393_216L
        body.put("max_tokens", minOf(requested, limit, 393_216L)).remove("max_completion_tokens")
        body.put("temperature", number(state.parameters.temperature))
        state.parameters.topP?.let { body.put("top_p", number(it)) }
        applyOfficialSampling(body, thinking)
        return true
    }

    private fun defaultTokens(body: RequestBody, thinking: Boolean): Long = when {
        !thinking -> 8_192L
        body.get("reasoning_effort") == JsonValue.text("max") -> 131_072L
        else -> 65_536L
    }

    /** Frozen passback.ensureOfficialDeepseekV4ReasoningPassback: empty reasoning_content placeholders. */
    fun healOfficialPassback(body: RequestBody, state: ResponsesRequestState) {
        if (!isOfficialV4(state)) return
        if ((body.get("thinking") as? JsonValue.Object)?.get("type")?.let(ResponsesJson::text) == "disabled") return
        val tools = body.get("tools") as? JsonValue.Array
        if (tools == null || tools.values.isEmpty()) return
        val messages = body.get("messages") as? JsonValue.Array ?: return
        var healed = false
        val updated = messages.values.map { message ->
            val healedMessage = healMessage(message)
            if (healedMessage != null) healed = true
            healedMessage ?: message
        }
        if (healed) body.put("messages", JsonValue.array(updated))
    }

    private fun healMessage(message: JsonValue): JsonValue.Object? {
        val obj = message as? JsonValue.Object ?: return null
        val isToolCallAssistant = ResponsesJson.text(ResponsesJson.field(obj, "role")) == "assistant" &&
            (ResponsesJson.array(ResponsesJson.field(obj, "tool_calls"))?.isNotEmpty() == true)
        if (!isToolCallAssistant) return null
        if (obj["reasoning_content"] is JsonValue.Text) return null
        return JsonValue.Object.from(LinkedHashMap(obj.asMap()).apply { put("reasoning_content", JsonValue.text("")) })
    }

    internal fun isOfficialDeepseekConfig(state: ResponsesRequestState): Boolean {
        val normalizedBaseUrl = state.baseUrl.trim().trimEnd('/').lowercase(Locale.ROOT)
        if (normalizedBaseUrl.isEmpty()) return state.providerType == "deepseek" || state.providerScope == "deepseek"
        return responsesBaseUrlHost(state.baseUrl) == "api.deepseek.com"
    }

    internal fun isOfficialV4(state: ResponsesRequestState): Boolean =
        hostProtocol(state) == HostProtocol.OFFICIAL && isV4EffortCapable(classifyModel(state.model))

    internal fun responsesBaseUrlHost(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/').lowercase(Locale.ROOT)
        val schemeIndex = normalized.indexOf("://")
        val withoutScheme = if (schemeIndex >= 0) normalized.substring(schemeIndex + 3) else normalized
        val hostPort = withoutScheme.split('/', '?', '#').first()
        return hostPort.substringBefore(':')
    }

    private fun hostProtocol(state: ResponsesRequestState): HostProtocol = when {
        state.baseUrl.lowercase(Locale.ROOT).contains("siliconflow") -> HostProtocol.SILICONFLOW
        state.providerType == "siliconflow" -> HostProtocol.SILICONFLOW
        isOfficialDeepseekConfig(state) -> HostProtocol.OFFICIAL
        else -> HostProtocol.OTHER
    }

    private fun resolveThinking(state: ResponsesRequestState, version: ModelVersion): Boolean = when {
        state.reasoning.flags.thinkingEnabled != null -> state.reasoning.flags.thinkingEnabled == true
        version == ModelVersion.V4 -> true
        state.supportsReasoning && isV4EffortCapable(version) -> true
        else -> state.thinkingEnabled
    }

    private fun shouldApplyReasoningEffort(
        state: ResponsesRequestState,
        version: ModelVersion,
        host: HostProtocol,
        enableThinking: Boolean,
    ): String? {
        if (!enableThinking || !isV4EffortCapable(version)) return null
        val effort = state.effort ?: return null
        if (host != HostProtocol.OFFICIAL && effort.lowercase(Locale.ROOT) == "xhigh") return "max"
        return normalizeV4Effort(effort)
    }

    private fun resolveSiliconflowBudget(state: ResponsesRequestState, version: ModelVersion): Long? {
        val budget = state.reasoning.thinkingBudget?.toLong()
        if (version != ModelVersion.V31 && version != ModelVersion.V32) return budget
        budget?.let { return it }
        val mapped = state.effort?.trim()?.lowercase(Locale.ROOT)?.let(::budgetFromEffort)
        return mapped ?: budget
    }

    private fun applyOfficialSampling(body: RequestBody, thinking: Boolean) {
        body.removeAll("presence_penalty", "frequency_penalty")
        if (thinking) {
            body.remove("temperature")
            val topP = body.get("top_p") as? JsonValue.Number
            if (topP != null) body.put("top_p", number(topP.literal.toDouble().coerceIn(0.95, 1.0)))
        } else {
            body.remove("top_p")
        }
    }

    private fun classifyModel(model: String): ModelVersion {
        val lower = model.lowercase(Locale.ROOT)
        return when {
            lower.contains("deepseek-v3.1") -> ModelVersion.V31
            lower.contains("deepseek-v3.2") -> ModelVersion.V32
            lower.contains("deepseek-v4") || lower.contains("deepseek-flash") -> ModelVersion.V4
            lower == "deepseek-chat" || lower == "deepseek-reasoner" -> ModelVersion.LEGACY_ALIAS
            else -> ModelVersion.UNKNOWN
        }
    }

    private fun normalizeV4Effort(effort: String): String? = when (effort.trim().lowercase(Locale.ROOT)) {
        "none", "unset" -> null
        "minimal", "low" -> "low"
        "medium", "high", "xhigh" -> "high"
        "max", "ultra" -> "max"
        else -> null
    }

    private fun budgetFromEffort(effort: String): Long? = when (effort) {
        "low" -> 2_048L
        "medium" -> 8_192L
        "high" -> 16_384L
        "xhigh", "max" -> 32_768L
        else -> null
    }

    private fun isV4EffortCapable(version: ModelVersion): Boolean = version == ModelVersion.V4 || version == ModelVersion.LEGACY_ALIAS

    private val CAPABLE_VERSIONS = setOf(ModelVersion.V31, ModelVersion.V32, ModelVersion.V4)
}
