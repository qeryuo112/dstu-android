package com.deepstudent.services.providers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeGoCatalogTest {
    @Test
    fun `catalog keeps the exact 13 frozen models`() {
        assertEquals(13, OPENCODE_GO_BUILTIN_MODELS.size)
        assertEquals("builtin-opencode-go-deepseek-v4.1-flash", OPENCODE_GO_BUILTIN_MODELS.first().id)
        assertEquals("deepseek-v4.1-flash", OPENCODE_GO_BUILTIN_MODELS.first().model)
        assertEquals("openai_chat_completions", OPENCODE_GO_BUILTIN_MODELS.first().protocol)
        assertEquals("builtin-opencode-go-qwen3.8-max", OPENCODE_GO_BUILTIN_MODELS[7].id)
        assertEquals("anthropic_messages", OPENCODE_GO_BUILTIN_MODELS[7].protocol)
        assertEquals("builtin-opencode-go-gpt-6-luna", OPENCODE_GO_BUILTIN_MODELS[11].id)
        assertEquals("openai_responses", OPENCODE_GO_BUILTIN_MODELS[11].protocol)
        assertEquals("builtin-opencode-go-gemini-4-argon", OPENCODE_GO_BUILTIN_MODELS[12].id)
        assertEquals("google_generate_content", OPENCODE_GO_BUILTIN_MODELS[12].protocol)
        assertTrue(OPENCODE_GO_BUILTIN_MODELS.all { it.maxOutputTokens == 32_768 })
        assertEquals(13, OPENCODE_GO_BUILTIN_MODELS.map { it.id }.toSet().size)
        assertEquals(13, OPENCODE_GO_BUILTIN_MODELS.map { it.model }.toSet().size)
    }

    @Test
    fun `detection matches vendor, provider type or go root only`() {
        assertTrue(OpenCodeGoCatalog.isOpencodeGoConfig("builtin-opencode-go", null, "https://example.com/v1"))
        assertTrue(OpenCodeGoCatalog.isOpencodeGoConfig(null, "opencode_go", "https://example.com/v1"))
        assertTrue(OpenCodeGoCatalog.isOpencodeGoConfig("custom-1", "openai", "https://opencode.ai/inference/go/openai/v1/"))
        assertTrue(OpenCodeGoCatalog.isOpencodeGoConfig(null, null, "https://OpenCode.ai/zen/go/v1"))
        assertFalse(OpenCodeGoCatalog.isOpencodeGoConfig("builtin-deepseek", "deepseek", "https://api.deepseek.com/v1"))
        assertFalse(OpenCodeGoCatalog.isOpencodeGoConfig(null, "openai", "https://opencode.ai/zen/v1"))
    }

    @Test
    fun `base url swaps only for go roots`() {
        assertEquals(
            "https://opencode.ai/inference/go/anthropic/v1",
            OpenCodeGoCatalog.baseUrlForProtocol("https://opencode.ai/inference/go/openai/v1", "anthropic_messages"),
        )
        assertEquals(
            "https://opencode.ai/inference/go/google/v1beta",
            OpenCodeGoCatalog.baseUrlForProtocol("https://opencode.ai/inference/go/openai/v1", "google_generate_content"),
        )
        assertEquals(
            "https://opencode.ai/inference/go/openai/v1",
            OpenCodeGoCatalog.baseUrlForProtocol("https://opencode.ai/inference/go/anthropic/v1", "openai_responses"),
        )
        assertEquals(
            "https://proxy.example.com/v1",
            OpenCodeGoCatalog.baseUrlForProtocol("https://proxy.example.com/v1", "anthropic_messages"),
        )
    }

    @Test
    fun `session values are sanitized and bounded`() {
        assertEquals("abcd", OpenCodeGoCatalog.sessionHeaderValue(" a b\r\nc/d "))
        assertNull(OpenCodeGoCatalog.sessionHeaderValue("  "))
        assertNull(OpenCodeGoCatalog.sessionHeaderValue(null))
        assertEquals(128, OpenCodeGoCatalog.sessionHeaderValue("x".repeat(500))?.length)
        assertEquals("fixturesession_01:go", OpenCodeGoCatalog.sessionHeaderValue("fixture session_01:🧪 go"))
    }

    @Test
    fun `identity headers carry the own agent and optional session`() {
        val withSession = OpenCodeGoCatalog.identityHeaders("sess_01HX-abc", "0.11.0")
        assertEquals(listOf("User-Agent" to "DeepStudent/0.11.0", "x-opencode-session" to "sess_01HX-abc"), withSession)
        val withoutSession = OpenCodeGoCatalog.identityHeaders("   ", "0.11.0")
        assertEquals(listOf("User-Agent" to "DeepStudent/0.11.0"), withoutSession)
    }

    @Test
    fun `protocol adapters follow the frozen mapping`() {
        assertEquals("anthropic", OpenCodeGoCatalog.modelAdapterForProtocol("anthropic_messages"))
        assertEquals("google", OpenCodeGoCatalog.modelAdapterForProtocol("google_generate_content"))
        assertEquals("general", OpenCodeGoCatalog.modelAdapterForProtocol("openai_chat_completions"))
    }
}
