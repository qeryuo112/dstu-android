package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderUsage

/**
 * Frozen usage projection (`providers/anthropic.ts` build_usage_event and
 * `AnthropicAdapter._build_merged_usage_event` at
 * e6b2657fd59014a73f5bbb1d14c2db732cf23e87). Cache fields take the largest
 * observed candidate. Android keeps nonnegative Long counters without narrowing;
 * missing counters stay absent, and an inconsistent total does not fail the turn.
 */
internal object AnthropicUsage {
    fun build(holder: JsonValue?): ProviderUsage {
        val usage = ResponsesJson.obj(holder)
        val input = countFirst(usage, "input_tokens", "prompt_tokens")
        val output = countFirst(usage, "output_tokens", "completion_tokens")
        val declaredTotal = nonNegative(usage?.i64At("total_tokens"))
        val cached = largestOf(
            nonNegative(usage?.i64At("cache_read_input_tokens")),
            detailValue(usage, "prompt_tokens_details", "cached_tokens"),
            detailValue(usage, "input_tokens_details", "cached_tokens"),
            nonNegative(usage?.i64At("prompt_cache_hit_tokens")),
            nonNegative(usage?.i64At("cached_tokens")),
        )
        val cacheWrite = largestOf(
            nonNegative(usage?.i64At("cache_creation_input_tokens")),
            detailValue(usage, "input_tokens_details", "cache_write_tokens"),
            nonNegative(usage?.i64At("cache_write_tokens")),
        )
        return ProviderUsage(
            inputTokens = input,
            outputTokens = output,
            totalTokens = consistentTotal(declaredTotal, input, output),
            cachedInputTokens = cached,
            cacheWriteInputTokens = cacheWrite,
            reasoningTokens = reasoningTokens(usage),
        )
    }

    /** Start-of-message usage backfills zero or missing counters at message end. */
    fun merged(terminal: JsonValue?, start: JsonValue?): ProviderUsage =
        build(mergeStartUsage(ResponsesJson.obj(terminal), ResponsesJson.obj(start)))

    private fun mergeStartUsage(terminal: JsonValue.Object?, start: JsonValue.Object?): JsonValue.Object? {
        if (terminal == null || start == null) return terminal
        val merged = AnthropicBlock().apply { putAll(terminal.asMap()) }
        for (key in MERGED_KEYS) backfillFromStart(merged, start, key)
        return merged.freeze()
    }

    /** Only a zero or missing counter adopts the start value, and only a nonzero one. */
    private fun backfillFromStart(merged: AnthropicBlock, start: JsonValue.Object, key: String) {
        val current = (merged[key] as? JsonValue.Number)?.asLongOrNull() ?: 0
        if (current != 0L) return
        val startValue = (start[key] as? JsonValue.Number)?.asLongOrNull() ?: return
        if (startValue != 0L) merged[key] = JsonValue.integer(startValue)
    }

    private fun reasoningTokens(usage: JsonValue.Object?): Long? {
        if (usage == null) return null
        if (usage.containsKey("reasoning_tokens")) return nonNegative(usage.i64At("reasoning_tokens"))
        usage.objectAt("completion_tokens_details")?.let { details ->
            details.i64At("reasoning_tokens")?.let { return nonNegative(it) }
        }
        return usage.objectAt("output_tokens_details")?.i64At("reasoning_tokens")?.let(::nonNegative)
    }

    private fun consistentTotal(declared: Long?, input: Long?, output: Long?): Long? {
        if (input == null || output == null) return declared
        if (input > Long.MAX_VALUE - output) return null
        val sum = input + output
        return if (declared == null || declared == sum) sum else null
    }

    private fun detailValue(holder: JsonValue.Object?, detailKey: String, key: String): Long? =
        holder?.objectAt(detailKey)?.let { nonNegative(it.i64At(key)) }

    private fun nonNegative(value: Long?): Long? = value?.takeIf { it >= 0 }

    private fun largestOf(vararg candidates: Long?): Long? = candidates.filterNotNull().maxOrNull()

    private fun countFirst(holder: JsonValue.Object?, vararg keys: String): Long? {
        holder ?: return null
        val key = keys.firstOrNull(holder::containsKey) ?: return null
        return nonNegative(holder.i64At(key))
    }

    private val MERGED_KEYS = listOf("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")
}

/** The frozen request converter narrows signed integers to i32. */
internal fun JsonValue.Object.i32At(key: String): Int? = i64At(key)?.toInt()
