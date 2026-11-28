package com.deepstudent.services.providers.data

import java.util.Locale

/**
 * Claude model generation rules
 * (`host/plugins/llm/src/adapters/anthropic.ts` and
 * `host/plugins/llm-providers/src/providers/anthropic.ts` at
 * e6b2657fd59014a73f5bbb1d14c2db732cf23e87): the Claude family decides whether
 * thinking rides a manual `budget_tokens` block, an `adaptive` block with an
 * `output_config.effort` level, or is unsupported for the wire adapter.
 */
internal enum class ClaudeGeneration { ADAPTIVE, MANUAL, UNSUPPORTED }

private val CLAUDE_FAMILIES = listOf("opus", "sonnet", "haiku", "fable", "mythos")

internal class ParsedClaudeModel(val family: String, val major: Int, val minor: Int?)

/** `llm_manager/adapters/anthropic.rs:65 parse_claude_model` (frozen port). */
internal fun parseClaudeModel(model: String): ParsedClaudeModel? {
    val tokens = model.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter(String::isNotEmpty)
    val familyIndex = tokens.indexOfFirst { it in CLAUDE_FAMILIES }
    if (familyIndex < 0) return null
    val family = CLAUDE_FAMILIES.first { it == tokens[familyIndex] }
    val after = asVersion(tokens.getOrNull(familyIndex + 1))
    if (after != null) return ParsedClaudeModel(family, after, asVersion(tokens.getOrNull(familyIndex + 2)))
    val numbers = mutableListOf<Int>()
    var index = familyIndex
    while (index > 0) {
        index -= 1
        val value = asVersion(tokens.getOrNull(index)) ?: break
        numbers += value
    }
    numbers.reverse()
    if (numbers.isNotEmpty()) return ParsedClaudeModel(family, numbers[0], numbers.getOrNull(1))
    return ParsedClaudeModel(family, 0, null)
}

private fun asVersion(token: String?): Int? {
    if (token == null || !Regex("^\\d+$").matches(token)) return null
    val value = token.toLongOrNull() ?: return null
    return if (value < 100) value.toInt() else null
}

/** Centralized generation decision shared by the request rules and wire headers. */
internal fun claudeGeneration(model: String): ClaudeGeneration {
    val parsed = parseClaudeModel(model) ?: return ClaudeGeneration.UNSUPPORTED
    return when (parsed.family) {
        "fable", "mythos" -> ClaudeGeneration.ADAPTIVE
        "haiku" -> haikuGeneration(parsed)
        "opus", "sonnet" -> opusSonnetGeneration(parsed)
        else -> ClaudeGeneration.UNSUPPORTED
    }
}

/** Opus/Sonnet split thinking by major/minor version. */
private fun opusSonnetGeneration(parsed: ParsedClaudeModel): ClaudeGeneration = when (parsed.major) {
    4 -> if ((parsed.minor ?: 0) >= 6) ClaudeGeneration.ADAPTIVE else ClaudeGeneration.MANUAL
    3 -> sonnet37Generation(parsed)
    else -> if (parsed.major >= 5) ClaudeGeneration.ADAPTIVE else ClaudeGeneration.UNSUPPORTED
}

private fun sonnet37Generation(parsed: ParsedClaudeModel): ClaudeGeneration =
    if (parsed.family == "sonnet" && parsed.minor == 7) ClaudeGeneration.MANUAL else ClaudeGeneration.UNSUPPORTED

/** Haiku keeps only the 4.x manual generation. */
private fun haikuGeneration(parsed: ParsedClaudeModel): ClaudeGeneration = when {
    parsed.major == 0 -> ClaudeGeneration.UNSUPPORTED
    parsed.major == 4 -> ClaudeGeneration.MANUAL
    else -> ClaudeGeneration.UNSUPPORTED
}

/** Fable / restricted Mythos models keep thinking always on. */
internal fun claudeThinkingAlwaysOn(model: String): Boolean {
    val parsed = parseClaudeModel(model) ?: return false
    return parsed.family == "fable" || parsed.family == "mythos"
}

/** `reasoning_effort` to the Anthropic effort levels of the adaptive generation. */
internal fun mapReasoningEffortToAnthropic(effort: String): String? = when (effort.trim().lowercase(Locale.ROOT)) {
    "low", "minimal" -> "low"
    "medium" -> "medium"
    "high" -> "high"
    "xhigh" -> "xhigh"
    "max" -> "max"
    else -> null
}

/** `llm_manager/adapters/anthropic.rs:161 map_budget_tokens_to_effort` (frozen port). */
internal fun mapBudgetTokensToEffort(budget: Long): String = when {
    budget < 8_192 -> "low"
    budget < 16_384 -> "medium"
    budget < 32_768 -> "high"
    else -> "max"
}

internal const val ANTHROPIC_MAX_BUDGET_TOKENS = 32_768
internal const val ANTHROPIC_MIN_BUDGET_TOKENS = 1_024
private const val ANTHROPIC_DEFAULT_BUDGET_TOKENS = 10_240L

/** `AnthropicAdapter.validateBudgetTokens` (frozen port): clamp to [1024, cap] and keep below max tokens. */
internal fun validateAnthropicBudgetTokens(budget: Long?, maxTokens: Long?, budgetCap: Long): Long {
    val requested = if (budget != null && budget > 0) budget else ANTHROPIC_DEFAULT_BUDGET_TOKENS
    var validated = minOf(budgetCap, maxOf(ANTHROPIC_MIN_BUDGET_TOKENS.toLong(), requested))
    if (maxTokens != null) {
        if (maxTokens <= 1) return 1
        if (maxTokens <= ANTHROPIC_MIN_BUDGET_TOKENS) return maxOf(1, maxTokens - 1)
        if (validated >= maxTokens) validated = maxOf(ANTHROPIC_MIN_BUDGET_TOKENS.toLong(), maxTokens - 1)
    }
    return validated
}

/** `isClaude45` gates the legacy sampling drop for disabled thinking. */
internal fun isClaude45(model: String): Boolean {
    val parsed = parseClaudeModel(model) ?: return false
    return parsed.family in setOf("opus", "sonnet", "haiku") && parsed.major == 4 && parsed.minor == 5
}
