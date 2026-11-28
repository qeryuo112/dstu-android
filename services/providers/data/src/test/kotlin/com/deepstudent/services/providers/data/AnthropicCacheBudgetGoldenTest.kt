package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frozen cache breakpoint budget guard (`llm-providers/tests/anthropic.test.ts`). */
class AnthropicCacheBudgetGoldenTest {
    private fun tool(name: String, marker: JsonValue.Object? = null): AnthropicBlock = AnthropicBlock().apply {
        put("name", JsonValue.text(name))
        put("input_schema", JsonValue.obj("type" to JsonValue.text("object")))
        marker?.let { put("cache_control", it) }
    }

    private fun system(text: String, marker: JsonValue.Object? = null): AnthropicBlock = AnthropicBlock().apply {
        put("type", JsonValue.text("text"))
        put("text", JsonValue.text(text))
        marker?.let { put("cache_control", it) }
    }

    private fun ephemeral(): JsonValue.Object = anthropicEphemeral()

    @Test
    fun budgetConstantMatchesTheAnthropicHardLimit() {
        assertEquals(4, ANTHROPIC_CACHE_BREAKPOINT_BUDGET)
    }

    @Test
    fun guardKeepsMarkersWithinBudget() {
        val tools = mutableListOf(tool("alpha"), tool("beta", ephemeral()))
        val blocks = mutableListOf(system("s1", ephemeral()), system("s2"), system("s3", ephemeral()))
        enforceAnthropicCacheBreakpointBudget(tools, blocks)
        assertNullMarker(tools[0])
        assertEquals(ephemeral(), tools[1]["cache_control"])
        assertEquals(ephemeral(), blocks[0]["cache_control"])
        assertFalse(blocks[1].containsKey("cache_control"))
        assertEquals(ephemeral(), blocks[2]["cache_control"])
    }

    @Test
    fun guardWithoutToolsKeepsSystemMarkersWithinBudget() {
        val blocks = mutableListOf(system("s1", ephemeral()), system("s2", ephemeral()), system("s3", ephemeral()))
        enforceAnthropicCacheBreakpointBudget(mutableListOf(), blocks)
        assertEquals(3, blocks.count { it.containsKey("cache_control") })
    }

    @Test
    fun guardStripsEarliestSystemMarkersOnOverflow() {
        val blocks = mutableListOf(
            system("s1", ephemeral()),
            system("s2", ephemeral()),
            system("s3", ephemeral()),
            system("s4", ephemeral()),
            system("s5", ephemeral()),
        )
        enforceAnthropicCacheBreakpointBudget(mutableListOf(), blocks)
        assertFalse(blocks[0].containsKey("cache_control"))
        assertFalse(blocks[1].containsKey("cache_control"))
        assertEquals(ephemeral(), blocks[2]["cache_control"])
        assertEquals(ephemeral(), blocks[3]["cache_control"])
        assertEquals(ephemeral(), blocks[4]["cache_control"])
    }

    @Test
    fun guardOverflowCrossesFromToolsIntoSystem() {
        val tools = mutableListOf(tool("t1", ephemeral()))
        val blocks = mutableListOf(
            system("s1", ephemeral()),
            system("s2", ephemeral()),
            system("s3", ephemeral()),
            system("s4", ephemeral()),
        )
        enforceAnthropicCacheBreakpointBudget(tools, blocks)
        assertEquals(0, tools.count { it.containsKey("cache_control") })
        assertFalse(blocks[0].containsKey("cache_control"))
        assertEquals(ephemeral(), blocks[1]["cache_control"])
        assertEquals(ephemeral(), blocks[2]["cache_control"])
        assertEquals(ephemeral(), blocks[3]["cache_control"])
    }

    @Test
    fun survivingMarkersKeepTheirPayloadVerbatim() {
        val tools = mutableListOf(
            tool("t1", ephemeral()),
            tool(
                "t2",
                JsonValue.obj(
                    "type" to JsonValue.text("ephemeral"),
                    "ttl" to JsonValue.text("1h"),
                ),
            ),
        )
        val blocks = mutableListOf(
            system("s1", ephemeral()),
            system(
                "s2",
                JsonValue.obj(
                    "type" to JsonValue.text("ephemeral"),
                    "ttl" to JsonValue.text("1h"),
                ),
            ),
        )
        enforceAnthropicCacheBreakpointBudget(tools, blocks)
        assertFalse(tools[0].containsKey("cache_control"))
        assertEquals("1h", ResponsesJson.text(ResponsesJson.field(tools[1]["cache_control"], "ttl")))
        assertEquals("1h", ResponsesJson.text(ResponsesJson.field(blocks[1]["cache_control"], "ttl")))
    }

    @Test
    fun emptyInputsAreNoOps() {
        val tools = mutableListOf<AnthropicBlock>()
        val blocks = mutableListOf<AnthropicBlock>()
        enforceAnthropicCacheBreakpointBudget(tools, blocks)
        assertEquals(0, tools.size)
        assertEquals(0, blocks.size)
    }

    @Test
    fun markerNestedInTheFunctionObjectIsNotAToolMarker() {
        val raw = JsonValue.obj(
            "type" to JsonValue.text("function"),
            "cache_control" to anthropicEphemeral(),
            "function" to JsonValue.obj(
                "name" to JsonValue.text("alpha_tool"),
                "parameters" to JsonValue.obj("type" to JsonValue.text("object")),
            ),
        )
        val converted = convertAnthropicToolDefinition(raw)
        converted!!
        assertTrue(converted.containsKey("cache_control"))
        assertEquals("alpha_tool", ResponsesJson.text(converted["name"]))
    }

    @Test
    fun droppedToolEntriesLeaveNoSideEffects() {
        val raw = JsonValue.obj(
            "type" to JsonValue.text("custom"),
            "cache_control" to anthropicEphemeral(),
            "function" to
                JsonValue.obj("name" to JsonValue.text("dropped_tool"), "parameters" to JsonValue.obj("type" to JsonValue.text("object"))),
        )
        val converted = convertAnthropicToolDefinition(raw)
        assertEquals(null, converted)
    }

    private fun assertNullMarker(block: AnthropicBlock) {
        assertFalse(block.containsKey("cache_control"))
    }
}
