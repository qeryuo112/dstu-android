package com.deepstudent.services.providers.backend

import com.deepstudent.services.providers.api.ProviderProtocol
import java.util.Collections
import java.util.Locale

/** S0 allowlist/default metadata, deliberately distinct from manual override policy. */
data class ProviderProtocolRecord(
    val providerType: String,
    val allowedProtocols: List<ProviderProtocol>,
    val defaultProtocol: ProviderProtocol,
    val official: Boolean,
    val supportsOpenaiResponses: Boolean = false,
    val displayName: String = providerType,
) {
    init {
        require(providerType.matches(PROVIDER_TYPE)) { "invalid provider type" }
        require(allowedProtocols.isNotEmpty() && allowedProtocols.distinct().size == allowedProtocols.size)
        require(defaultProtocol in allowedProtocols) { "default protocol is not in allowlist" }
        require(displayName.isNotBlank())
    }

    override fun toString(): String =
        "ProviderProtocolRecord(provider=$providerType, default=${defaultProtocol.wireName}, official=$official)"

    private companion object {
        val PROVIDER_TYPE = Regex("[a-z0-9][a-z0-9_]{0,63}")
    }
}

object ProviderRegistry {
    val records: List<ProviderProtocolRecord> = Collections.unmodifiableList(
        listOf(
            record(
                "openai",
                "OpenAI",
                true,
                ProviderProtocol.OPENAI_RESPONSES,
                true,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                ProviderProtocol.OPENAI_RESPONSES,
            ),
            record("openai_codex", "OpenAI Codex OAuth", false, ProviderProtocol.OPENAI_RESPONSES, true, ProviderProtocol.OPENAI_RESPONSES),
            record(
                "deepseek",
                "DeepSeek",
                true,
                ProviderProtocol.OPENAI_RESPONSES,
                true,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                ProviderProtocol.OPENAI_RESPONSES,
            ),
            record(
                "grok",
                "Grok",
                true,
                ProviderProtocol.OPENAI_RESPONSES,
                true,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                ProviderProtocol.OPENAI_RESPONSES,
            ),
            record(
                "xai",
                "xAI",
                true,
                ProviderProtocol.OPENAI_RESPONSES,
                true,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                ProviderProtocol.OPENAI_RESPONSES,
            ),
            record("anthropic", "Anthropic", true, ProviderProtocol.ANTHROPIC_MESSAGES, false, ProviderProtocol.ANTHROPIC_MESSAGES),
            record("google", "Google", true, ProviderProtocol.GOOGLE_GENERATE_CONTENT, false, ProviderProtocol.GOOGLE_GENERATE_CONTENT),
            record("gemini", "Gemini", true, ProviderProtocol.GOOGLE_GENERATE_CONTENT, false, ProviderProtocol.GOOGLE_GENERATE_CONTENT),
            record(
                "siliconflow",
                "SiliconFlow",
                false,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                false,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            ),
            record(
                "qwen",
                "Qwen",
                true,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                true,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                ProviderProtocol.OPENAI_RESPONSES,
            ),
            record("zhipu", "Zhipu", true, ProviderProtocol.OPENAI_CHAT_COMPLETIONS, false, ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            record(
                "doubao",
                "Doubao",
                true,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                true,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                ProviderProtocol.OPENAI_RESPONSES,
            ),
            record(
                "minimax",
                "MiniMax",
                true,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                false,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                ProviderProtocol.ANTHROPIC_MESSAGES,
            ),
            record("moonshot", "Moonshot", true, ProviderProtocol.OPENAI_CHAT_COMPLETIONS, false, ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            record("nvidia", "NVIDIA", true, ProviderProtocol.OPENAI_CHAT_COMPLETIONS, false, ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            record("mimo", "MiMo", true, ProviderProtocol.OPENAI_CHAT_COMPLETIONS, false, ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            record(
                "ernie",
                "ERNIE",
                true,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                true,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                ProviderProtocol.OPENAI_RESPONSES,
            ),
            record("mistral", "Mistral", true, ProviderProtocol.OPENAI_CHAT_COMPLETIONS, false, ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            record(
                "custom",
                "Custom",
                false,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                false,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                ProviderProtocol.OPENAI_RESPONSES,
            ),
            record(
                "general",
                "General",
                false,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                false,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                ProviderProtocol.OPENAI_RESPONSES,
            ),
            record(
                "opencode_go", "OpenCode Go", true, ProviderProtocol.OPENAI_CHAT_COMPLETIONS, true,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS, ProviderProtocol.OPENAI_RESPONSES,
                ProviderProtocol.ANTHROPIC_MESSAGES, ProviderProtocol.GOOGLE_GENERATE_CONTENT,
            ),
            record(
                "openrouter",
                "OpenRouter",
                false,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                false,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                ProviderProtocol.OPENAI_RESPONSES,
            ),
            record(
                "ollama",
                "Ollama",
                false,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                false,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                ProviderProtocol.OPENAI_RESPONSES,
            ),
        ),
    )

    val byType: Map<String, ProviderProtocolRecord> = records.associateBy { it.providerType }

    fun find(providerType: String): ProviderProtocolRecord? = byType[providerType.trim().lowercase(Locale.ROOT)]

    private fun record(
        providerType: String,
        displayName: String,
        official: Boolean,
        defaultProtocol: ProviderProtocol,
        supportsOpenaiResponses: Boolean,
        vararg protocols: ProviderProtocol,
    ): ProviderProtocolRecord = ProviderProtocolRecord(
        providerType = providerType,
        allowedProtocols = Collections.unmodifiableList(protocols.toList()),
        defaultProtocol = defaultProtocol,
        official = official,
        supportsOpenaiResponses = supportsOpenaiResponses,
        displayName = displayName,
    )
}

/**
 * Frozen manual compatibility: explicit custom/proxy protocols may select native
 * Anthropic/Google transports even when the S0 catalog default is Chat Completions.
 */
object ProviderProtocolPolicy {
    private val manualNativeAllowed = setOf("custom", "general", "openrouter", "ollama")

    fun allowsManual(providerType: String, protocol: ProviderProtocol): Boolean {
        val record = ProviderRegistry.find(providerType) ?: return false
        return protocol in record.allowedProtocols || record.providerType in manualNativeAllowed
    }
}
