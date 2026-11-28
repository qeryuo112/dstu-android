package com.deepstudent.services.providers.backend

import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderProtocolSource
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity
import com.deepstudent.services.providers.api.VendorProtocolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ProviderRegistryMatrixTest(
    private val providerType: String,
    private val model: String,
    private val expectedProtocol: ProviderProtocol,
    private val expectedPathFragment: String,
) {
    private val resolver = ProviderConfigResolver()

    @Test
    fun frozenProviderResolvesWithoutNetworkOrAdapter() {
        val vendor = VendorConfig(
            identity = VendorIdentity("vendor-$providerType", providerType, providerType),
            connection = VendorConnection(
                baseUrl(providerType),
                if (providerType ==
                    "openai_codex"
                ) {
                    AuthMode.OPENAI_CODEX_OAUTH
                } else {
                    AuthMode.NONE
                },
            ),
        )
        val result = resolver.resolve(ProviderSelection(vendor, ModelProfile("model-$providerType", vendor.id, model)))
        assertTrue("$providerType failed: ${(result as? ProviderResult.Failure)?.failure?.code}", result is ProviderResult.Success)
        val resolved = (result as ProviderResult.Success).value
        assertEquals("$providerType protocol", expectedProtocol, resolved.protocol)
        assertEquals(ProviderProtocolSource.DEFAULT, resolved.protocolSource)
        assertTrue(resolved.endpoint.startsWith("https://"))
        assertTrue("$providerType route: ${resolved.endpoint}", resolved.endpoint.contains(expectedPathFragment))
    }

    companion object {
        private fun case(
            provider: String,
            protocol: ProviderProtocol,
            model: String = "test-model",
            path: String = when (protocol) {
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> "/chat/completions"
                ProviderProtocol.OPENAI_RESPONSES -> "/responses"
                ProviderProtocol.ANTHROPIC_MESSAGES -> "/messages"
                ProviderProtocol.GOOGLE_GENERATE_CONTENT -> ":streamGenerateContent?alt=sse"
            },
        ): Array<Any> = arrayOf(provider, model, protocol, path)

        private fun base(provider: String): String = when (provider) {
            "openai" -> "https://api.openai.com/v1"
            "openai_codex" -> "https://chatgpt.com/backend-api/codex"
            "deepseek" -> "https://api.deepseek.com/v1"
            "anthropic" -> "https://api.anthropic.com/v1"
            "google" -> "https://generativelanguage.googleapis.com"
            "gemini" -> "https://generativelanguage.googleapis.com"
            "opencode_go" -> "https://opencode.ai/inference/go/openai/v1"
            else -> "https://$provider.example/v1"
        }

        private fun baseUrl(provider: String): String = base(provider)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> = listOf(
            case("openai", ProviderProtocol.OPENAI_RESPONSES, "gpt-5"),
            case("openai_codex", ProviderProtocol.OPENAI_RESPONSES, "gpt-5-codex"),
            case("deepseek", ProviderProtocol.OPENAI_RESPONSES, "deepseek-flash"),
            case("grok", ProviderProtocol.OPENAI_RESPONSES),
            case("xai", ProviderProtocol.OPENAI_RESPONSES),
            case("anthropic", ProviderProtocol.ANTHROPIC_MESSAGES),
            case("google", ProviderProtocol.GOOGLE_GENERATE_CONTENT),
            case("gemini", ProviderProtocol.GOOGLE_GENERATE_CONTENT),
            case("siliconflow", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            case("qwen", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            case("zhipu", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            case("doubao", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            case("minimax", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            case("moonshot", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            case("nvidia", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            case("mimo", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            case("ernie", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            case("mistral", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            case("custom", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            case("general", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            case("opencode_go", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            case("openrouter", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            case("ollama", ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
        )
    }
}
