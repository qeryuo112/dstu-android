package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import java.util.Locale

internal object GeneralRules : OpenAiChatRequestRules {
    override fun applyReasoning(body: RequestBody, state: RequestState): EarlyReturn {
        if (state.profile != OpenAiChatProviderProfile.SILICONFLOW || !state.supportsReasoning) {
            return applyGenericReasoning(body, state)
        }
        body.put("enable_thinking", state.thinkingEnabled)
        body.removeAll("reasoning", "thinking")
        if (state.thinkingEnabled) {
            val normalized = normalizeSiliconFlowEffort(state.effort)
            if (normalized != null) {
                body.put("reasoning_effort", normalized).remove("thinking_budget")
            } else {
                body.remove("reasoning_effort")
                state.reasoning.thinkingBudget?.let { body.put("thinking_budget", it.coerceIn(128, 32_768)) }
                    ?: body.remove("thinking_budget")
            }
        } else {
            body.removeAll("thinking_budget", "reasoning_effort")
        }
        return EarlyReturn.CONTINUE
    }

    override fun applyCommon(body: RequestBody, state: RequestState) = body.putCommon(state)
}

internal object QwenRules : OpenAiChatRequestRules {
    override fun applyReasoning(body: RequestBody, state: RequestState): EarlyReturn {
        val shape = qwenShape(state)
        if (shape.siliconflow) return applySiliconFlowQwen(body, state, shape)
        if (shape.dashscope) body.remove("frequency_penalty")
        val enabled = shape.forced || state.thinkingEnabled
        if (state.supportsReasoning || shape.forced) applyQwenThinking(body, state, shape, enabled)
        applyQwenEffort(body, state, shape, enabled)
        return EarlyReturn.CONTINUE
    }

    private fun applyQwenThinking(body: RequestBody, state: RequestState, shape: QwenShape, enabled: Boolean) {
        body.put("enable_thinking", enabled)
        if (shape.dashscope && state.model.supportsQwenPreserve()) {
            body.put("preserve_thinking", enabled && state.reasoning.includeThoughts)
        }
        applyQwenBudget(body, state, shape, enabled)
    }

    private fun applyQwenBudget(body: RequestBody, state: RequestState, shape: QwenShape, enabled: Boolean) {
        val budget = state.reasoning.thinkingBudget ?: return
        if (!enabled || (shape.isQwen38 && normalizeQwen38Effort(state.effort, shape.omni) != null)) return
        val sanitized = if (shape.siliconflow) budget.coerceIn(128, 32_768) else budget.coerceAtLeast(0)
        if (sanitized > 0) body.put("thinking_budget", sanitized)
    }

    private fun applyQwenEffort(body: RequestBody, state: RequestState, shape: QwenShape, enabled: Boolean) {
        if (shape.dashscope) applyDashscopeQwenEffort(body, shape, enabled) else applyQwenCompatibleEffort(body, state, shape, enabled)
    }

    private fun applyDashscopeQwenEffort(body: RequestBody, shape: QwenShape, enabled: Boolean) {
        if (shape.omni && !enabled) body.put("reasoning_effort", "none") else body.remove("reasoning_effort")
    }

    private fun applyQwenCompatibleEffort(body: RequestBody, state: RequestState, shape: QwenShape, enabled: Boolean) {
        val effort = state.effort ?: return
        if (shape.omni && !enabled) {
            body.put("reasoning_effort", "none")
        } else if (effort != "none") {
            val normalized = if (shape.isQwen38) normalizeQwen38Effort(effort, shape.omni) else effort
            if (normalized != null) body.put("reasoning_effort", normalized)
        }
    }

    private fun applySiliconFlowQwen(body: RequestBody, state: RequestState, shape: QwenShape): EarlyReturn {
        body.put("enable_thinking", state.thinkingEnabled)
        body.removeAll("thinking", "thinking_budget")
        if (!state.thinkingEnabled) {
            if (shape.omni) body.put("reasoning_effort", "none") else body.remove("reasoning_effort")
        } else {
            val effort = state.effort
            val normalized = effort?.let { qwenSiliconFlowEffort(it, shape.omni) }
            if (normalized == null) body.remove("reasoning_effort") else body.put("reasoning_effort", normalized)
        }
        return EarlyReturn.CONTINUE
    }

    private fun qwenShape(state: RequestState): QwenShape = QwenShape(
        siliconflow = state.provider == "siliconflow" || state.profile == OpenAiChatProviderProfile.SILICONFLOW ||
            state.baseUrl.contains("siliconflow"),
        dashscope = state.provider == "qwen" || state.baseUrl.isDashscope(),
        forced = state.model.isQwenForcedThinking(),
        isQwen38 = state.model.isQwen38(),
        omni = state.model.isQwen38() && state.model.contains("omni", ignoreCase = true),
    )

    override fun applyCommon(body: RequestBody, state: RequestState) = body.putCommon(state)
}

private data class QwenShape(
    val siliconflow: Boolean,
    val dashscope: Boolean,
    val forced: Boolean,
    val isQwen38: Boolean,
    val omni: Boolean,
)

private fun qwenSiliconFlowEffort(effort: String, omni: Boolean): String? =
    if (effort == "none" && omni) "none" else normalizeSiliconFlowEffort(effort)

internal object ZhipuRules : OpenAiChatRequestRules {
    override fun applyReasoning(body: RequestBody, state: RequestState): EarlyReturn {
        body.removeAll("frequency_penalty", "presence_penalty")
        val glm53 = state.model.isGlm53()
        applyZhipuThinking(body, state, glm53)
        applyZhipuEffort(body, state, glm53)
        if (state.model.supportsZhipuToolStream() && body.has("tools")) body.put("tool_stream", true)
        return EarlyReturn.CONTINUE
    }

    private fun applyZhipuThinking(body: RequestBody, state: RequestState, glm53: Boolean) {
        val canThink = state.model.supportsZhipuThinking()
        if (!(canThink && !glm53) && !state.supportsReasoning && !glm53) return
        val enabled = glm53 || state.thinkingEnabled
        val thinking = JsonValue.Object.Builder().put("type", if (enabled) "enabled" else "disabled")
        if (state.reasoning.includeThoughts) thinking.put("clear_thinking", false)
        body.put("thinking", thinking.build())
    }

    private fun applyZhipuEffort(body: RequestBody, state: RequestState, glm53: Boolean) {
        if (!state.model.supportsZhipuEffort()) return
        val effort = state.effort ?: return
        when {
            glm53 -> body.put("reasoning_effort", normalizeGlm53Effort(effort))
            effort in ZHIPU_EFFORTS -> body.put("reasoning_effort", effort)
        }
    }

    override fun applyCommon(body: RequestBody, state: RequestState) = body.putCommon(state)
}

private val ZHIPU_EFFORTS = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

internal object DoubaoRules : OpenAiChatRequestRules {
    override fun applyReasoning(body: RequestBody, state: RequestState): EarlyReturn {
        if (!state.supportsReasoning && !state.isReasoning) return EarlyReturn.CONTINUE
        val type = when {
            !state.thinkingEnabled -> "disabled"
            state.model.supportsDoubaoAuto() && state.effort in setOf("auto", "medium") -> "auto"
            else -> "enabled"
        }
        body.put("thinking", JsonValue.obj("type" to JsonValue.text(type)))
        return EarlyReturn.CONTINUE
    }

    override fun applyCommon(body: RequestBody, state: RequestState) = body.putCommon(state)
}

internal object MiniMaxRules : OpenAiChatRequestRules {
    override fun applyReasoning(body: RequestBody, state: RequestState): EarlyReturn {
        body.removeAll("enable_thinking", "thinking_budget", "include_thoughts")
        if (state.model.minimaxVersion() >= 3) {
            val current = (body.get("thinking") as? JsonValue.Object)?.get("type") as? JsonValue.Text
            val enabled = when (current?.value) {
                "disabled" -> false
                "adaptive", "enabled" -> true
                else -> state.thinkingEnabled
            }
            body.put("thinking", JsonValue.obj("type" to JsonValue.text(if (enabled) "adaptive" else "disabled")))
        } else {
            body.remove("thinking")
        }
        state.reasoning.reasoningSplit?.let { body.put("reasoning_split", it) }
        return EarlyReturn.STOP
    }

    override fun applyCommon(body: RequestBody, state: RequestState) = Unit
}

private fun String.isDashscope(): Boolean = contains("dashscope.aliyuncs.com") || contains("dashscope-intl.aliyuncs.com") ||
    contains(".maas.aliyuncs.com") || contains("qianwenaiapi.com")

private fun String.isQwenForcedThinking(): Boolean {
    val lower = lowercase(Locale.ROOT)
    return lower.contains("qwq") || listOf(
        "qwen3.7-max-preview",
        "qwen3-7-max-preview",
        "qwen3.7-max-2026-05-17",
        "qwen3-7-max-2026-05-17",
        "qwen3.7-max-20260517",
        "qwen3-7-max-20260517",
    ).any(lower::contains) || (lower.contains("qwen3") && lower.split('-', '_', '/').any { it == "thinking" })
}

private fun String.supportsQwenPreserve() = lowercase(Locale.ROOT).let { it.contains("qwen3.6") || it.contains("qwen3.7") }
private fun String.isQwen38() = trim().lowercase(Locale.ROOT).split('/').last().startsWith("qwen3.8")
private fun normalizeQwen38Effort(effort: String?, omni: Boolean): String? = when (effort) {
    null, "", "unset" -> null
    "none" -> if (omni) "none" else null
    "minimal", "low" -> "low"
    "medium" -> "medium"
    "high", "xhigh", "max", "ultra" -> "xhigh"
    else -> null
}

private fun String.supportsZhipuThinking(): Boolean {
    val lower = lowercase(Locale.ROOT)
    if (lower.contains("-flash") || lower.contains("4.1v")) return false
    return listOf("glm-4.5", "glm-4.6", "glm-4.7", "glm4.5", "glm4.6", "glm4.7", "glm-5", "glm5").any(lower::contains)
}
private fun String.zhipuVersion(): Pair<Int, Int>? {
    val match = Regex("glm(?:-)?([0-9]+)(?:[.\\-]([0-9]{1,2}))?").find(lowercase(Locale.ROOT)) ?: return null
    return match.groupValues[1].toInt() to (match.groupValues[2].toIntOrNull() ?: 0)
}
private fun String.supportsZhipuEffort() = zhipuVersion()?.let { (major, minor) -> major > 5 || (major == 5 && minor >= 2) } == true
private fun String.isGlm53() = trim().lowercase(Locale.ROOT).split('/').last().let { it.startsWith("glm-5.3") || it.startsWith("glm5.3") }
private fun normalizeGlm53Effort(effort: String) = when (effort) {
    "minimal", "low" -> "low"
    "medium", "high", "xhigh" -> "high"
    "none", "unset", "off", "disabled" -> "low"
    else -> "max"
}
private fun String.supportsZhipuToolStream() =
    listOf("glm-4.6", "glm-4.7", "glm4.6", "glm4.7", "glm-5", "glm5").any(lowercase(Locale.ROOT)::contains)
private fun String.supportsDoubaoAuto() = lowercase(Locale.ROOT).let { it.contains("seed") || it.contains("thinking-pro-m") }
private fun String.minimaxVersion() =
    Regex("(?:^|[^a-z0-9])m(\\d+)", RegexOption.IGNORE_CASE).find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0
