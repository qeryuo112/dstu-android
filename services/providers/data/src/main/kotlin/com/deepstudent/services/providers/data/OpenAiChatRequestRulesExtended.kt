package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import java.util.Locale

internal object MoonshotRules : OpenAiChatRequestRules {
    override fun applyReasoning(body: RequestBody, state: RequestState): EarlyReturn {
        if (state.model.isK3()) return applyK3Reasoning(body, state)
        if (state.model.isK25OrLater()) return applyK25Reasoning(body, state)
        if (state.model.isThinkingKimi()) applyLegacyThinking(body)
        return EarlyReturn.CONTINUE
    }

    private fun applyK3Reasoning(body: RequestBody, state: RequestState): EarlyReturn {
        body.removeAll("thinking", "enable_thinking", "thinking_budget", "include_thoughts")
        body.put("reasoning_effort", normalizeK3Effort(state.effort))
        return EarlyReturn.STOP
    }

    private fun applyK25Reasoning(body: RequestBody, state: RequestState): EarlyReturn {
        val enabled = state.model.isK27PlusCode() || state.thinkingEnabled
        val thinking = JsonValue.Object.Builder().put("type", if (enabled) "enabled" else "disabled")
        if (state.model.supportsThinkingKeep() && enabled && (state.model.isK27PlusCode() || state.reasoning.includeThoughts)) {
            thinking.put("keep", "all")
        }
        body.put("thinking", thinking.build())
        body.removeAll("temperature", "top_p", "n", "presence_penalty", "frequency_penalty")
        val existing = (body.get("max_completion_tokens") as? JsonValue.Number)?.asLongOrNull()?.takeIf { it > 0 }
        body.put("max_completion_tokens", existing ?: 32_768)
        if (enabled) restoreToolChoice(body)
        return EarlyReturn.STOP
    }

    private fun applyLegacyThinking(body: RequestBody) {
        body.put("temperature", number(1.0))
        val current = (body.get("max_tokens") as? JsonValue.Number)?.asLongOrNull() ?: 0
        if (current < 16_000) body.put("max_tokens", 32_000)
    }

    private fun restoreToolChoice(body: RequestBody) {
        val choice = (body.get("tool_choice") as? JsonValue.Text)?.value
        if (choice != null && choice != "auto" && choice != "none") body.put("tool_choice", "auto")
    }

    override fun applyCommon(body: RequestBody, state: RequestState) = body.putCommon(state)
}

internal object MimoRules : OpenAiChatRequestRules {
    override fun applyReasoning(body: RequestBody, state: RequestState): EarlyReturn {
        body.removeAll("enable_thinking", "thinking_budget", "include_thoughts", "reasoning_effort")
        applyMimoThinking(body, state)
        if ((body.get("tool_choice") as? JsonValue.Text)?.value?.let { it != "auto" } == true) body.put("tool_choice", "auto")
        return EarlyReturn.STOP
    }

    private fun applyMimoThinking(body: RequestBody, state: RequestState) {
        val tts = state.model.contains("tts", ignoreCase = true)
        if (!tts && (state.supportsReasoning || state.isReasoning)) {
            body.put("thinking", JsonValue.obj("type" to JsonValue.text(if (state.thinkingEnabled) "enabled" else "disabled")))
        } else {
            body.remove("thinking")
        }
    }

    override fun applyCommon(body: RequestBody, state: RequestState) = Unit
}

internal object ErnieRules : OpenAiChatRequestRules {
    override fun applyReasoning(body: RequestBody, state: RequestState): EarlyReturn {
        if (state.model.contains("deepseek", ignoreCase = true)) {
            applyHostedDeepseekEffort(body, state)
        } else if (state.effort != null) {
            body.remove("reasoning_effort")
        }
        if (state.model.isErnieThinking() || state.supportsReasoning || state.isReasoning) {
            body.put("thinking", JsonValue.obj("type" to JsonValue.text(if (state.thinkingEnabled) "enabled" else "disabled")))
            body.remove("enable_thinking")
        }
        if (!state.model.contains("deepseek", ignoreCase = true)) body.remove("thinking_budget")
        return EarlyReturn.CONTINUE
    }

    private fun applyHostedDeepseekEffort(body: RequestBody, state: RequestState) {
        when (state.effort) {
            "low" -> body.put("reasoning_effort", "low")
            "medium" -> body.put("reasoning_effort", "medium")
            "high" -> body.put("reasoning_effort", "high")
            "xhigh" -> body.put("reasoning_effort", "high")
            "max" -> body.put("reasoning_effort", "max")
        }
    }

    override fun applyCommon(body: RequestBody, state: RequestState) {
        state.parameters.minP?.let { body.put("min_p", number(it)) }
        state.parameters.topK?.let { body.put("top_k", it) }
        state.reasoning.repetitionPenalty?.let { body.put("penalty_score", number(it)) }
        state.parameters.typedExtraBody.forEach { (key, raw) -> body.putTypedExtra(key, raw) }
        state.parameters.extraBody.forEach { (key, raw) -> body.putExtra(key, raw) }
    }
}

internal object MistralRules : OpenAiChatRequestRules {
    override fun applyReasoning(body: RequestBody, state: RequestState): EarlyReturn {
        applyMistralEffort(body, state)
        if (state.model.contains("magistral", ignoreCase = true)) {
            if (state.thinkingEnabled) body.put("prompt_mode", "reasoning") else body.putNull("prompt_mode")
        }
        return EarlyReturn.CONTINUE
    }

    private fun applyMistralEffort(body: RequestBody, state: RequestState) {
        if (!state.model.supportsMistralEffort()) return
        val requested = if (!state.thinkingEnabled) "none" else state.effort ?: return
        body.put("reasoning_effort", MISTRAL_EFFORTS[requested] ?: "low")
    }

    override fun applyCommon(body: RequestBody, state: RequestState) {
        state.parameters.minP?.let { body.put("min_p", number(it)) }
        state.parameters.topK?.let { body.put("top_k", it) }
        state.reasoning.repetitionPenalty?.let { body.put("repetition_penalty", number(it)) }
        state.parameters.typedExtraBody.forEach { (key, raw) -> body.putTypedExtra(key, raw) }
        state.parameters.extraBody.forEach { (key, raw) -> body.putExtra(key, raw) }
    }
}

private val MISTRAL_EFFORTS = mapOf(
    "none" to "none",
    "minimal" to "low",
    "low" to "low",
    "medium" to "medium",
    "high" to "high",
    "xhigh" to "high",
    "max" to "high",
)

internal fun applyGenericReasoning(body: RequestBody, state: RequestState): EarlyReturn {
    val effort = state.effort
    removeUnsupportedGenericSampling(body, state, effort)
    body.removeAll("enable_thinking", "thinking_budget", "include_thoughts", "thinking", "output_config")
    if (effort == null) {
        applyImplicitGenericEffort(body, state)
        return EarlyReturn.CONTINUE
    }
    return applyExplicitGenericEffort(body, effort)
}

private fun removeUnsupportedGenericSampling(body: RequestBody, state: RequestState, effort: String?) {
    val reasoning = state.supportsReasoning || state.isReasoning
    val keepReasoningDefault = effort == null || (effort != "unset" && effort != "none" && effort in VALID_EFFORTS)
    if (reasoning && keepReasoningDefault) {
        body.removeAll("temperature", "top_p", "presence_penalty", "frequency_penalty", "logprobs")
    }
}

private fun applyExplicitGenericEffort(body: RequestBody, effort: String): EarlyReturn {
    if (effort == "unset") {
        body.removeAll("reasoning_effort", "reasoning")
        return EarlyReturn.STOP
    }
    val normalized = normalizeStandardEffort(effort)
    if (normalized == null) {
        body.removeAll("reasoning_effort", "reasoning")
        return EarlyReturn.CONTINUE
    }
    body.put("reasoning_effort", normalized).remove("reasoning")
    return if (normalized == "none") EarlyReturn.STOP else EarlyReturn.CONTINUE
}

private fun applyImplicitGenericEffort(body: RequestBody, state: RequestState) {
    if (!state.supportsReasoning && !state.isReasoning) return
    val effort = if (state.thinkingEnabled) state.reasoning.thinkingBudget?.let(::effortFromBudget) ?: "medium" else "none"
    body.put("reasoning_effort", effort).remove("reasoning")
}

internal fun normalizeStandardEffort(effort: String): String? = when (effort.trim().lowercase(Locale.ROOT)) {
    "none", "disabled", "off", "false" -> "none"
    "minimal" -> "minimal"
    "low" -> "low"
    "medium", "enabled", "adaptive", "auto", "on", "true" -> "medium"
    "high" -> "high"
    "xhigh", "max" -> "xhigh"
    else -> null
}

internal fun normalizeSiliconFlowEffort(effort: String?): String? = when (effort?.trim()?.lowercase(Locale.ROOT)) {
    "minimal", "low" -> "low"
    "medium" -> "medium"
    "high" -> "high"
    "xhigh" -> "xhigh"
    "max", "ultra" -> "max"
    else -> null
}

internal fun effortFromBudget(budget: Int): String = when {
    budget < 0 -> "medium"
    budget == 0 -> "none"
    budget <= 2048 -> "low"
    budget <= 8192 -> "medium"
    budget <= 16_384 -> "high"
    else -> "xhigh"
}

private fun String.isK3() =
    Regex("kimi-k(\\d+)", RegexOption.IGNORE_CASE).find(this)?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 3 } == true
private fun String.isK25OrLater(): Boolean {
    val match = Regex("kimi-k(\\d+)(?:[.\\-](\\d+))?", RegexOption.IGNORE_CASE).find(this) ?: return false
    val major = match.groupValues[1].toIntOrNull() ?: return false
    val minor = match.groupValues[2].toIntOrNull() ?: 0
    return major > 2 || (major == 2 && minor >= 5)
}
private fun String.supportsThinkingKeep() = Regex("kimi-k2[.\\-](\\d+)", RegexOption.IGNORE_CASE).find(lowercase(Locale.ROOT))
    ?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 6 } == true
private fun String.isK27PlusCode() = isK25OrLater() && lowercase(Locale.ROOT).let {
    Regex("kimi-k2[.\\-](\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull()?.let { minor ->
        minor >= 7
    } == true &&
        it.contains("code")
}
private fun normalizeK3Effort(effort: String?): String = when (effort) {
    "minimal", "low" -> "low"
    "medium" -> "medium"
    "high" -> "high"
    "xhigh", "max" -> "max"
    else -> "medium"
}
private fun String.isThinkingKimi() = lowercase(Locale.ROOT).let { it.contains("thinking") || isK25OrLater() || isK3() }
private fun String.isErnieThinking() = lowercase(Locale.ROOT).let {
    it.contains("ernie-5.0-thinking") || it.contains("ernie-5-thinking") || it.contains("ernie5-thinking") ||
        it.contains("ernie-x1") || it.contains("ernie_x1")
}
private fun String.supportsMistralEffort(): Boolean {
    if (contains("magistral", ignoreCase = true)) return false
    val slug = trim().lowercase(Locale.ROOT).split('/').last()
    return slug in setOf("mistral-medium-latest", "mistral-small-latest", "mistral-medium-3-5", "mistral-medium-3.5", "mistral-small-4") ||
        slug.startsWith("mistral-medium-3-5-") || slug.startsWith("mistral-medium-3.5-") || slug.startsWith("mistral-small-4-")
}
