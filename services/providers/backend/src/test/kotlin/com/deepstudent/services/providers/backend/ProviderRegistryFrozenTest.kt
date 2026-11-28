package com.deepstudent.services.providers.backend

import com.deepstudent.services.providers.api.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRegistryFrozenTest {
    @Test
    fun frozenRowsAndSelectionMetadataMatchDesktop() {
        assertEquals(23, ProviderRegistry.records.size)
        assertEquals(
            listOf(
                "openai", "openai_codex", "deepseek", "grok", "xai", "anthropic", "google", "gemini",
                "siliconflow", "qwen", "zhipu", "doubao", "minimax", "moonshot", "nvidia", "mimo",
                "ernie", "mistral", "custom", "general", "opencode_go", "openrouter", "ollama",
            ),
            ProviderRegistry.records.map { it.providerType },
        )
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, ProviderRegistry.find("openai")!!.defaultProtocol)
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, ProviderRegistry.find("deepseek")!!.defaultProtocol)
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, ProviderRegistry.find("grok")!!.defaultProtocol)
        assertEquals(ProviderProtocol.OPENAI_CHAT_COMPLETIONS, ProviderRegistry.find("qwen")!!.defaultProtocol)
        assertEquals(ProviderProtocol.OPENAI_CHAT_COMPLETIONS, ProviderRegistry.find("doubao")!!.defaultProtocol)
        assertEquals(ProviderProtocol.OPENAI_CHAT_COMPLETIONS, ProviderRegistry.find("ernie")!!.defaultProtocol)
        assertTrue(ProviderRegistry.find("minimax")!!.allowedProtocols.contains(ProviderProtocol.ANTHROPIC_MESSAGES))
        assertEquals(ProviderProtocol.ANTHROPIC_MESSAGES, ProviderRegistry.find("anthropic")!!.defaultProtocol)
        assertEquals(ProviderProtocol.GOOGLE_GENERATE_CONTENT, ProviderRegistry.find("gemini")!!.defaultProtocol)
        assertTrue(ProviderRegistry.find("opencode_go")!!.allowedProtocols.contains(ProviderProtocol.ANTHROPIC_MESSAGES))
        assertTrue(ProviderRegistry.find("opencode_go")!!.allowedProtocols.contains(ProviderProtocol.GOOGLE_GENERATE_CONTENT))
    }
}
