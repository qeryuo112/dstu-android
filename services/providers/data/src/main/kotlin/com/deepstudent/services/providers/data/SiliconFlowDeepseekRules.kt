package com.deepstudent.services.providers.data

import java.util.Locale

/** DeepSeek models hosted by SiliconFlow; not the official DeepSeek Responses/Chat adapter. */
internal object SiliconFlowDeepseekRules : OpenAiChatRequestRules {
    override fun applyReasoning(body: RequestBody, state: RequestState): EarlyReturn {
        val version = deepseekVersion(state.model)
        if (!version.nativeThinking && !state.supportsReasoning) return EarlyReturn.CONTINUE
        val enabled = thinkingEnabled(state, version)
        body.put("enable_thinking", enabled).remove("thinking")
        if (version.usesEffort) {
            body.remove("thinking_budget")
            val effort = normalizedEffort(state.model, state.effort).takeIf { enabled }
            if (effort != null) body.put("reasoning_effort", effort) else body.remove("reasoning_effort")
        } else {
            body.remove("reasoning_effort")
            val budget = state.reasoning.thinkingBudget ?: BUDGET_BY_EFFORT[state.effort].takeIf { version.nativeThinking }
            budget?.let { body.put("thinking_budget", it.coerceIn(128, 32_768)) }
        }
        return EarlyReturn.CONTINUE
    }

    override fun applyCommon(body: RequestBody, state: RequestState) = body.putCommon(state)

    private fun thinkingEnabled(state: RequestState, version: SiliconFlowDeepseekVersion): Boolean =
        state.resolved.runtime.reasoning.flags.thinkingEnabled
            ?: (version == SiliconFlowDeepseekVersion.V4 || (state.supportsReasoning && version.usesEffort) || state.thinkingEnabled)

    private fun normalizedEffort(model: String, effort: String?): String? = when (overrideGatewayEffort(model, effort)) {
        "minimal", "low" -> "low"
        "medium", "high" -> "high"
        "xhigh", "max", "ultra" -> "max"
        else -> null
    }

    private fun overrideGatewayEffort(model: String, effort: String?): String? {
        if (model.trim().lowercase(Locale.ROOT) !in GATEWAY_OVERRIDE_MODELS || effort !in GATEWAY_OVERRIDE_EFFORTS) return effort
        return if (effort == "xhigh" || effort == "max") "max" else "high"
    }

    private fun deepseekVersion(model: String): SiliconFlowDeepseekVersion {
        val id = model.lowercase(Locale.ROOT)
        return when {
            "deepseek-v3.1" in id || "deepseek-v3.2" in id -> SiliconFlowDeepseekVersion.V3
            "deepseek-v4" in id || "deepseek-flash" in id -> SiliconFlowDeepseekVersion.V4
            id in LEGACY_ALIASES -> SiliconFlowDeepseekVersion.ALIAS
            else -> SiliconFlowDeepseekVersion.UNKNOWN
        }
    }

    private val BUDGET_BY_EFFORT = mapOf("low" to 2_048, "medium" to 8_192, "high" to 16_384, "xhigh" to 32_768, "max" to 32_768)
    private val GATEWAY_OVERRIDE_MODELS = setOf("pro/deepseek-ai/deepseek-v4", "deepseek-ai/deepseek-v4-flash")
    private val GATEWAY_OVERRIDE_EFFORTS = setOf("minimal", "low", "medium", "high", "xhigh", "max")
    private val LEGACY_ALIASES = setOf("deepseek-chat", "deepseek-reasoner")
}

private enum class SiliconFlowDeepseekVersion(val nativeThinking: Boolean, val usesEffort: Boolean) {
    V3(true, false),
    V4(true, true),
    ALIAS(false, true),
    UNKNOWN(false, false),
}
