package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue

/**
 * Anthropic cache breakpoint budget
 * (`providers/anthropic.ts` ANTHROPIC_CACHE_BREAKPOINT_BUDGET and
 * `providers/mod.rs:3049 enforce_anthropic_cache_breakpoint_budget` at
 * e6b2657fd59014a73f5bbb1d14c2db732cf23e87): at most four `cache_control`
 * breakpoints exist per request; the final slot belongs to the top-level
 * `cache_control` marker, so tools and system blocks share three slots and the
 * earliest markers are stripped first on overflow.
 */
internal const val ANTHROPIC_CACHE_BREAKPOINT_BUDGET = 4

/** Frozen `{ "type": "ephemeral" }` marker written by the default tail breakpoints. */
internal fun anthropicEphemeral(): JsonValue.Object = JsonValue.obj("type" to JsonValue.text("ephemeral"))

internal typealias AnthropicBlock = LinkedHashMap<String, JsonValue>

/**
 * Mutating budget guard over still-unfrozen wire blocks. Tool markers drop in
 * list order (`delete` keeps key order); system markers use indexmap
 * `swap_remove` semantics (`llm-providers/src/json_map.ts` removeKey), so the
 * serialized key order of the surviving blocks matches the frozen wire bytes.
 */
internal fun enforceAnthropicCacheBreakpointBudget(tools: MutableList<AnthropicBlock>, systemBlocks: MutableList<AnthropicBlock>) {
    val overflow = markerCount(tools) + markerCount(systemBlocks) - (ANTHROPIC_CACHE_BREAKPOINT_BUDGET - 1)
    if (overflow <= 0) return
    stripSystemMarkers(systemBlocks, stripToolMarkers(tools, overflow))
}

private fun markerCount(blocks: List<AnthropicBlock>): Int = blocks.count { it.containsKey("cache_control") }

private fun stripToolMarkers(tools: List<AnthropicBlock>, overflow: Int): Int {
    var remaining = overflow
    for (tool in tools) {
        if (remaining == 0) break
        if (tool.remove("cache_control") != null) remaining -= 1
    }
    return remaining
}

private fun stripSystemMarkers(blocks: List<AnthropicBlock>, overflow: Int) {
    var remaining = overflow
    for (block in blocks) {
        if (remaining == 0) return
        if (ResponsesJson.swapRemove(block, "cache_control") != null) remaining -= 1
    }
}

/** Blocks freeze into wire JSON preserving insertion order. */
internal fun AnthropicBlock.freeze(): JsonValue.Object = JsonValue.Object.from(this)
