package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frozen desktop expectations (`llm-providers/tests/anthropic.test.ts`). */
class AnthropicModelsGoldenTest {
    @Test
    fun claudeGenerationMatchesModelFamilies() {
        assertEquals(ClaudeGeneration.ADAPTIVE, claudeGeneration("claude-opus-4-8"))
        assertEquals(ClaudeGeneration.ADAPTIVE, claudeGeneration("claude-sonnet-5"))
        assertEquals(ClaudeGeneration.ADAPTIVE, claudeGeneration("claude-fable-5"))
        assertEquals(ClaudeGeneration.ADAPTIVE, claudeGeneration("anthropic.claude-opus-4-8"))
        assertEquals(ClaudeGeneration.MANUAL, claudeGeneration("claude-sonnet-4-5-20250929"))
        assertEquals(ClaudeGeneration.MANUAL, claudeGeneration("claude-3-7-sonnet"))
        assertEquals(ClaudeGeneration.MANUAL, claudeGeneration("claude-haiku-4-5"))
        assertEquals(ClaudeGeneration.UNSUPPORTED, claudeGeneration("claude-2"))
        assertEquals(ClaudeGeneration.UNSUPPORTED, claudeGeneration("gpt-4o"))
    }

    @Test
    fun budgetTokensMapToEffortLevels() {
        assertEquals("medium", mapBudgetTokensToEffort(10_240))
        assertEquals("low", mapBudgetTokensToEffort(4_096))
        assertEquals("high", mapBudgetTokensToEffort(20_000))
        assertEquals("max", mapBudgetTokensToEffort(40_000))
    }

    @Test
    fun effortLevelsMapToAnthropicVocabulary() {
        assertEquals("low", mapReasoningEffortToAnthropic("minimal"))
        assertEquals("low", mapReasoningEffortToAnthropic(" low "))
        assertEquals("xhigh", mapReasoningEffortToAnthropic("xhigh"))
        assertNull(mapReasoningEffortToAnthropic("unset"))
    }

    @Test
    fun budgetValidationClampsAndStaysBelowMaxTokens() {
        assertEquals(10_240, validateAnthropicBudgetTokens(null, null, 32_768))
        assertEquals(1_024, validateAnthropicBudgetTokens(512, null, 32_768))
        assertEquals(32_768, validateAnthropicBudgetTokens(64_000, null, 32_768))
        assertEquals(1, validateAnthropicBudgetTokens(2_000, 1, 32_768))
        assertEquals(1_023, validateAnthropicBudgetTokens(2_000, 1_024, 32_768))
        assertEquals(8_191, validateAnthropicBudgetTokens(9_000, 8_192, 32_768))
    }

    @Test
    fun claude45AndAlwaysOnModelsAreRecognized() {
        assertTrue(isClaude45("claude-sonnet-4-5"))
        assertFalse(isClaude45("claude-sonnet-4-6"))
        assertTrue(claudeThinkingAlwaysOn("claude-fable-5"))
        assertTrue(claudeThinkingAlwaysOn("claude-mythos-5"))
        assertFalse(claudeThinkingAlwaysOn("claude-opus-4-8"))
    }

    @Test
    fun toolNamesSanitizeOutsideTheAllowedAlphabet() {
        assertEquals("lookup_weather", sanitizeAnthropicToolName("lookup_weather"))
        assertEquals("look_vp", sanitizeAnthropicToolName("look.vp"))
        assertEquals("restore", restoreAnthropicToolName("restore"))
    }

    @Test
    fun historyToolCallArgumentsKeepNullAndDropInvalidAsEmptyObject() {
        assertEquals(
            JsonValue.Null,
            anthropicHistoryInput("null"),
        )
        assertEquals(
            JsonValue.obj("q" to JsonValue.text("x")),
            anthropicHistoryInput("{\"q\":\"x\"}"),
        )
        assertEquals(JsonValue.Object.EMPTY, anthropicHistoryInput("{broken"))
    }

    private fun anthropicHistoryInput(rawArguments: String): JsonValue = runCatching { JsonValue.parse(rawArguments) }
        .getOrNull()
        ?: JsonValue.Object.EMPTY
}
