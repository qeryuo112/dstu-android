package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderTool
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import java.util.Locale

/** Enumerable request adapters from the frozen desktop registry. */
enum class OpenAiChatProviderProfile(val wireProvider: String? = null) {
    GENERAL,
    SILICONFLOW("siliconflow"),
    SILICONFLOW_DEEPSEEK,
    QWEN("qwen"),
    ZHIPU("zhipu"),
    DOUBAO("doubao"),
    MINIMAX("minimax"),
    MOONSHOT("moonshot"),
    NVIDIA("nvidia"),
    MIMO("mimo"),
    ERNIE("ernie"),
    MISTRAL("mistral"),
    ;

    companion object {
        fun forProvider(provider: String): OpenAiChatProviderProfile? {
            val normalized = provider.trim().lowercase(Locale.ROOT)
            return entries.firstOrNull { it.wireProvider == normalized }
        }
    }
}

data class OpenAiChatRequestPlan(val profile: OpenAiChatProviderProfile, val body: JsonValue.Object)

object OpenAiChatRequestPlanner {
    fun plan(request: ProviderAdapterRequest): OpenAiChatRequestPlan {
        val profile = resolveProfile(request.resolved)
        return OpenAiChatRequestPlan(profile, OpenAiChatRequest.body(request, profile))
    }

    /** Mirrors frozen adapter routing, including aggregator handling and SiliconFlow/NVIDIA exceptions. */
    fun resolveProfile(resolved: ResolvedProviderConfig): OpenAiChatProviderProfile {
        val adapter = resolved.adapterId.trim().lowercase(Locale.ROOT)
        val provider = resolved.providerType.trim().lowercase(Locale.ROOT)
        val scope = resolved.providerScope.trim().lowercase(Locale.ROOT)
        if (provider == "siliconflow" || scope == "siliconflow") return siliconFlowProfile(adapter)
        if (AGGREGATOR_PROVIDERS.any { it == adapter || it == provider || it == scope }) return OpenAiChatProviderProfile.GENERAL
        return SCOPED_PROFILES[scope] ?: PROVIDER_PROFILES[provider] ?: PROVIDER_PROFILES[adapter] ?: OpenAiChatProviderProfile.GENERAL
    }

    private fun siliconFlowProfile(adapter: String): OpenAiChatProviderProfile = when (adapter) {
        "qwen" -> OpenAiChatProviderProfile.QWEN
        "deepseek" -> OpenAiChatProviderProfile.SILICONFLOW_DEEPSEEK
        else -> OpenAiChatProviderProfile.SILICONFLOW
    }

    private val AGGREGATOR_PROVIDERS = setOf(
        "custom", "general", "openrouter", "together", "fireworks", "groq",
        "one-api", "one_api", "sub2api", "cpa",
    )
    private val PROVIDER_PROFILES = OpenAiChatProviderProfile.entries
        .filter { it.wireProvider != null }
        .associateBy { checkNotNull(it.wireProvider) }
    private val SCOPED_PROFILES = PROVIDER_PROFILES + mapOf("nvidia" to OpenAiChatProviderProfile.NVIDIA)
}

internal class RequestBody(private val entries: LinkedHashMap<String, JsonValue>) {
    fun get(key: String): JsonValue? = entries[key]
    fun has(key: String): Boolean = entries.containsKey(key)
    fun put(key: String, value: JsonValue): RequestBody = apply { entries[key] = value }
    fun put(key: String, value: String): RequestBody = put(key, JsonValue.text(value))
    fun put(key: String, value: Int): RequestBody = put(key, JsonValue.integer(value.toLong()))
    fun put(key: String, value: Long): RequestBody = put(key, JsonValue.integer(value))
    fun put(key: String, value: Boolean): RequestBody = put(key, JsonValue.bool(value))
    fun putNull(key: String): RequestBody = put(key, JsonValue.Null)
    fun remove(key: String): RequestBody = apply { entries.remove(key) }
    fun removeAll(vararg keys: String): RequestBody = apply { keys.forEach { entries.remove(it) } }
    fun putExtra(key: String, raw: String) {
        if (key.isBlank() || has(key) || key in PROTECTED_EXTRA_KEYS) return
        val trimmed = raw.trim()
        val value = if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            JsonValue.text(raw)
        } else {
            runCatching { JsonValue.parse(raw) }.getOrElse { JsonValue.text(raw) }
        }
        put(key, value)
    }

    fun putTypedExtra(key: String, raw: String) {
        if (key.isBlank() || has(key) || key in PROTECTED_EXTRA_KEYS) return
        put(key, runCatching { JsonValue.parse(raw) }.getOrElse { JsonValue.text(raw) })
    }
    fun build(): JsonValue.Object = JsonValue.Object.from(entries)
}

internal enum class EarlyReturn { CONTINUE, STOP }

internal interface OpenAiChatRequestRules {
    fun applyReasoning(body: RequestBody, state: RequestState): EarlyReturn
    fun applyCommon(body: RequestBody, state: RequestState)
}

internal data class RequestState(val request: ProviderAdapterRequest, val profile: OpenAiChatProviderProfile) {
    val resolved: ResolvedProviderConfig get() = request.resolved
    val model: String get() = resolved.profile.model
    val provider: String get() = resolved.providerType.lowercase(Locale.ROOT)
    val baseUrl: String get() = resolved.baseUrl.lowercase(Locale.ROOT)
    val reasoning get() = resolved.runtime.reasoning
    val parameters get() = resolved.runtime.parameters
    val effort: String? get() = reasoning.reasoningEffort?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
    val supportsReasoning: Boolean get() = resolved.runtime.features.reasoning
    val isReasoning: Boolean get() = resolved.runtime.isReasoning
    val thinkingEnabled: Boolean get() = resolved.runtime.thinkingEnabled
    val maxTokens: Long get() = parameters.maxOutputTokens.toLong().coerceAtMost(
        resolved.runtime.maxTokensLimit?.toLong()?.takeIf { it > 0 } ?: Long.MAX_VALUE,
    )
    val toolsEnabled: Boolean get() = resolved.runtime.capabilities.tools && request.tools.isNotEmpty()
    val hasToolMessages: Boolean get() = request.messages.any { it.role == ProviderRole.TOOL }
    val adapter: String get() = resolved.profile.adapter.wireName.lowercase(Locale.ROOT)
    val providerScope: String get() = resolved.providerScope.lowercase(Locale.ROOT)

    fun isMimo(): Boolean = provider == "mimo" || providerScope == "mimo" || profile == OpenAiChatProviderProfile.MIMO ||
        adapter == "mimo" || baseUrl.contains("xiaomimimo.com") || model.lowercase(Locale.ROOT).startsWith("mimo-v")

    fun isMistral(): Boolean = provider == "mistral" || providerScope == "mistral" || profile == OpenAiChatProviderProfile.MISTRAL ||
        adapter == "mistral" || baseUrl.contains("mistral.ai") || model.lowercase(Locale.ROOT).let {
            it.split('/').last().startsWith("mistral-") || it.split('/').last().startsWith("magistral-")
        }

    fun isSiliconFlow(): Boolean = provider == "siliconflow" || providerScope == "siliconflow" ||
        baseUrl.contains("siliconflow.cn") || baseUrl.contains("siliconflow.com")

    fun isQwen(): Boolean = provider == "qwen" || providerScope == "qwen" || profile == OpenAiChatProviderProfile.QWEN ||
        adapter == "qwen" || baseUrl.contains("dashscope.aliyuncs.com") || baseUrl.contains("dashscope-intl.aliyuncs.com") ||
        baseUrl.contains(".maas.aliyuncs.com") || baseUrl.contains("qianwenaiapi.com")

    fun putSampling(body: RequestBody) {
        body.put("temperature", number(parameters.temperature))
        parameters.topP?.let { body.put("top_p", number(it)) }
        parameters.frequencyPenalty?.let { body.put("frequency_penalty", number(it)) }
        reasoning.presencePenalty?.let { body.put("presence_penalty", number(it)) }
    }

    fun putTools(body: RequestBody, choice: String? = "auto") {
        if (!toolsEnabled) return
        val cleaned = cleanTools(request.tools)
        if (cleaned.values.isEmpty()) return
        body.put("tools", cleaned)
        if (choice != null && !(isQwen() && hasToolMessages)) body.put("tool_choice", choice)
    }

    private fun cleanTools(source: List<ProviderTool>): JsonValue.Array {
        val names = mutableSetOf<String>()
        return JsonValue.array(
            source.mapNotNull { tool ->
                if (!names.add(tool.name)) null else tool.wire(this)
            },
        )
    }

    private fun RequestState.parameters(raw: String): JsonValue.Object {
        val parsed = runCatching { JsonValue.parseObject(raw) }.getOrNull() ?: JsonValue.Object.EMPTY
        val schema = if (usesMoonshotToolSchema(model, provider, adapter, baseUrl)) {
            normalizeMoonshotToolSchema(parsed)
        } else {
            parsed
        }
        val properties = schema["properties"] as? JsonValue.Object ?: JsonValue.Object.EMPTY
        return JsonValue.Object.from(
            LinkedHashMap(schema.asMap()).apply {
                putIfAbsent("type", JsonValue.text("object"))
                putIfAbsent("properties", properties)
            },
        )
    }

    private fun ProviderTool.wire(state: RequestState): JsonValue.Object = JsonValue.obj(
        "type" to JsonValue.text("function"),
        "function" to JsonValue.obj(
            "name" to JsonValue.text(name),
            "description" to JsonValue.text(description),
            "parameters" to state.parameters(inputSchemaJson),
        ),
    )
}

internal object OpenAiChatRequestRulesResolver {
    private val RULES = mapOf(
        OpenAiChatProviderProfile.GENERAL to GeneralRules,
        OpenAiChatProviderProfile.SILICONFLOW to GeneralRules,
        OpenAiChatProviderProfile.SILICONFLOW_DEEPSEEK to SiliconFlowDeepseekRules,
        OpenAiChatProviderProfile.QWEN to QwenRules,
        OpenAiChatProviderProfile.ZHIPU to ZhipuRules,
        OpenAiChatProviderProfile.DOUBAO to DoubaoRules,
        OpenAiChatProviderProfile.MINIMAX to MiniMaxRules,
        OpenAiChatProviderProfile.MOONSHOT to MoonshotRules,
        OpenAiChatProviderProfile.NVIDIA to GeneralRules,
        OpenAiChatProviderProfile.MIMO to MimoRules,
        OpenAiChatProviderProfile.ERNIE to ErnieRules,
        OpenAiChatProviderProfile.MISTRAL to MistralRules,
    )

    fun rules(profile: OpenAiChatProviderProfile): OpenAiChatRequestRules = checkNotNull(RULES[profile])
}

internal fun RequestBody.putCommon(state: RequestState) {
    state.parameters.minP?.let { put("min_p", number(it)) }
    state.parameters.topK?.let { put("top_k", it) }
    state.reasoning.repetitionPenalty?.let { put("repetition_penalty", number(it)) }
    state.reasoning.reasoningSplit?.let { put("reasoning_split", it) }
    state.parameters.typedExtraBody.forEach { (key, raw) -> putTypedExtra(key, raw) }
    state.parameters.extraBody.forEach { (key, raw) -> putExtra(key, raw) }
}

internal fun RequestBody.putTokens(field: String, value: Long) {
    if (field == "max_tokens") {
        put("max_tokens", value).remove("max_completion_tokens")
    } else {
        put("max_completion_tokens", value).remove("max_tokens")
    }
}

internal fun number(value: Double): JsonValue.Number = JsonValue.Number.raw(value.toString())

internal val VALID_EFFORTS = setOf(
    "none", "disabled", "off", "false", "minimal", "low", "medium", "enabled",
    "adaptive", "auto", "on", "true", "high", "xhigh", "max", "unset",
)

private val PROTECTED_EXTRA_KEYS = setOf(
    "model", "messages", "stream", "tools", "tool_choice", "temperature", "top_p", "top_k",
    "min_p", "frequency_penalty", "presence_penalty", "repetition_penalty", "penalty_score", "max_tokens",
    "max_completion_tokens", "thinking", "enable_thinking", "thinking_budget", "preserve_thinking", "reasoning_effort",
    "reasoning_split", "prompt_mode", "response_format",
)
