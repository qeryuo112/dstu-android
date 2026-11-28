package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ModelAdapter
import com.deepstudent.services.providers.api.ModelCapabilities
import com.deepstudent.services.providers.api.ModelFeatures
import com.deepstudent.services.providers.api.ModelParameters
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ModelRuntime
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderProtocolSource
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderRoute
import com.deepstudent.services.providers.api.ProviderTool
import com.deepstudent.services.providers.api.ReasoningParameters
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatProviderProfilesTest {
    @Test
    fun tenProviderIdsResolveToEnumerableRequestProfiles() {
        val expected = mapOf(
            "siliconflow" to OpenAiChatProviderProfile.SILICONFLOW,
            "qwen" to OpenAiChatProviderProfile.QWEN,
            "zhipu" to OpenAiChatProviderProfile.ZHIPU,
            "doubao" to OpenAiChatProviderProfile.DOUBAO,
            "minimax" to OpenAiChatProviderProfile.MINIMAX,
            "moonshot" to OpenAiChatProviderProfile.MOONSHOT,
            "nvidia" to OpenAiChatProviderProfile.NVIDIA,
            "mimo" to OpenAiChatProviderProfile.MIMO,
            "ernie" to OpenAiChatProviderProfile.ERNIE,
            "mistral" to OpenAiChatProviderProfile.MISTRAL,
        )
        expected.forEach { (provider, profile) ->
            assertEquals(profile, OpenAiChatRequestPlanner.resolveProfile(resolved(provider).config))
        }
        assertEquals(OpenAiChatProviderProfile.GENERAL, OpenAiChatRequestPlanner.resolveProfile(resolved("custom").config))
        assertEquals(
            OpenAiChatProviderProfile.QWEN,
            OpenAiChatRequestPlanner.resolveProfile(resolved("siliconflow", ModelAdapter.QWEN, adapterId = "qwen").config),
        )
    }

    @Test
    fun profilesEmitProviderSpecificWireFields() {
        val qwen = plan(
            "qwen",
            model = "qwen3.7-plus",
            adapter = ModelAdapter.QWEN,
            reasoning = ReasoningParameters(reasoningEffort = "high", thinkingBudget = 2048, includeThoughts = true),
        )
        assertEquals(true, qwen.bool("enable_thinking"))
        assertEquals(null, qwen.text("reasoning_effort"))
        assertFalse(qwen.containsKey("frequency_penalty"))

        val zhipu = plan("zhipu", "glm-5.3", ModelAdapter.ZHIPU, ReasoningParameters(reasoningEffort = "minimal"))
        assertEquals("enabled", ((zhipu["thinking"] as JsonValue.Object)["type"] as JsonValue.Text).value)
        assertEquals("low", zhipu.text("reasoning_effort"))

        val doubao = plan("doubao", "doubao-seed-1-6", ModelAdapter.DOUBAO, ReasoningParameters(reasoningEffort = "medium"))
        assertEquals("auto", ((doubao["thinking"] as JsonValue.Object)["type"] as JsonValue.Text).value)

        val minimax = plan("minimax", "minimax-m3", ModelAdapter.MINIMAX)
        assertEquals("adaptive", ((minimax["thinking"] as JsonValue.Object)["type"] as JsonValue.Text).value)

        val moonshot = plan("moonshot", "kimi-k2.5", ModelAdapter.MOONSHOT)
        assertEquals("enabled", ((moonshot["thinking"] as JsonValue.Object)["type"] as JsonValue.Text).value)
        assertTrue(moonshot.containsKey("max_completion_tokens"))
        assertFalse(moonshot.containsKey("temperature"))

        val nvidia = plan(
            "nvidia",
            "nvidia/nemotron-3",
            ModelAdapter.GENERAL,
            ReasoningParameters(reasoningEffort = "high"),
            isReasoning = false,
        )
        assertEquals("high", nvidia.text("reasoning_effort"))
        assertEquals(2048L, (nvidia["max_tokens"] as JsonValue.Number).asLongOrNull())

        val mimo = plan("mimo", "mimo-v2.5-pro", ModelAdapter.MIMO)
        assertEquals(2048L, (mimo["max_completion_tokens"] as JsonValue.Number).asLongOrNull())
        assertEquals("enabled", ((mimo["thinking"] as JsonValue.Object)["type"] as JsonValue.Text).value)

        val ernie = plan("ernie", "ernie-5.0-thinking-latest", ModelAdapter.ERNIE)
        assertEquals("enabled", ((ernie["thinking"] as JsonValue.Object)["type"] as JsonValue.Text).value)

        val mistral = plan("mistral", "mistral-small-latest", ModelAdapter.MISTRAL, ReasoningParameters(reasoningEffort = "high"))
        assertEquals("high", mistral.text("reasoning_effort"))
        assertEquals(2048L, (mistral["max_tokens"] as JsonValue.Number).asLongOrNull())
    }

    @Test
    fun toolsSamplingExtraBodyAndGenericCompatibilityAreEmitted() {
        val body = plan(
            "custom",
            "custom-model",
            ModelAdapter.GENERAL,
            ReasoningParameters(reasoningEffort = "medium"),
            tools = listOf(ProviderTool("lookup", "Lookup", """{"type":"object","properties":{"q":{"type":"string"}}}""")),
            extra = mapOf("custom_flag" to "true", "custom_text" to "hello"),
            isReasoning = false,
        )
        assertEquals("medium", body.text("reasoning_effort"))
        assertEquals(0.7, (body["temperature"] as JsonValue.Number).literal.toDouble(), 0.0)
        assertEquals(2048L, (body["max_tokens"] as JsonValue.Number).asLongOrNull())
        assertEquals("auto", body.text("tool_choice"))
        assertEquals(1, (body["tools"] as JsonValue.Array).values.size)
        assertEquals(true, (body["custom_flag"] as JsonValue.Bool).value)
        assertEquals("hello", body.text("custom_text"))
    }

    private fun plan(
        provider: String,
        model: String = "test-model",
        adapter: ModelAdapter = ModelAdapter.GENERAL,
        reasoning: ReasoningParameters = ReasoningParameters(),
        tools: List<ProviderTool> = emptyList(),
        extra: Map<String, String> = emptyMap(),
        isReasoning: Boolean = true,
    ): JsonValue.Object {
        val setup = resolved(provider, adapter, model, reasoning, extra, isReasoning = isReasoning)
        return OpenAiChatRequestPlanner.plan(
            ProviderAdapterRequest(
                setup.config,
                listOf(ProviderMessage(ProviderRole.USER, "hello")),
                tools,
                "session",
            ),
        ).body
    }

    private fun resolved(
        provider: String,
        adapter: ModelAdapter = ModelAdapter.GENERAL,
        model: String = "test-model",
        reasoning: ReasoningParameters = ReasoningParameters(),
        extra: Map<String, String> = emptyMap(),
        adapterId: String = provider,
        isReasoning: Boolean = true,
    ): Setup {
        val vendor = VendorConfig(
            VendorIdentity("vendor", provider, provider),
            VendorConnection("https://provider.example/v1"),
        )
        val runtime = ModelRuntime(
            capabilities = ModelCapabilities(tools = true),
            features = ModelFeatures(reasoning = true),
            reasoning = ReasoningParameters(
                reasoningEffort = reasoning.reasoningEffort,
                thinkingBudget = reasoning.thinkingBudget,
                includeThoughts = reasoning.includeThoughts,
                reasoningMode = reasoning.reasoningMode,
                repetitionPenalty = reasoning.repetitionPenalty,
                presencePenalty = reasoning.presencePenalty,
                reasoningSplit = reasoning.reasoningSplit,
                flags = com.deepstudent.services.providers.api.ReasoningFlags(isReasoning = isReasoning),
            ),
            parameters = ModelParameters(maxOutputTokens = 2048, extraBody = extra),
        )
        val config = ResolvedProviderConfig(
            vendor = vendor,
            profile = ModelProfile("model", vendor.id, model, adapter = adapter),
            runtime = runtime,
            providerType = provider,
            providerScope = provider,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            protocolSource = ProviderProtocolSource.MANUAL,
            route = ProviderRoute(
                baseUrl = vendor.baseUrl,
                endpoint = vendor.baseUrl + "/chat/completions",
                authMode = AuthMode.NONE,
                credential = null,
                headers = emptyList(),
                adapterId = adapterId,
            ),
        )
        return Setup(config)
    }

    private data class Setup(val config: ResolvedProviderConfig)

    private fun JsonValue.Object.text(key: String): String? = (this[key] as? JsonValue.Text)?.value

    private fun JsonValue.Object.bool(key: String): Boolean? = (this[key] as JsonValue.Bool)?.value
}
