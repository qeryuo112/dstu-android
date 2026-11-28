package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.CredentialReference
import com.deepstudent.services.providers.api.ModelAdapter
import com.deepstudent.services.providers.api.ModelFeatures
import com.deepstudent.services.providers.api.ModelParameters
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ModelRuntime
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderHeader
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderProtocolSource
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderRoute
import com.deepstudent.services.providers.api.ProviderTool
import com.deepstudent.services.providers.api.ReasoningFlags
import com.deepstudent.services.providers.api.ReasoningParameters
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frozen wire conversion expectations
 * (`llm-providers/tests/anthropic.test.ts` at e6b2657fd59014a73f5bbb1d14c2db732cf23e87)
 * driven through the full Android request pipeline.
 */
class AnthropicRequestGoldenTest {
    @Test
    fun adaptiveGenerationRewritesEnabledThinkingAndDerivesEffort() {
        val wire = build("claude-sonnet-5", reasoning = ReasoningParameters(thinkingBudget = 10_240))
        assertEquals(JsonValue.obj("type" to JsonValue.text("adaptive")), wire.json["thinking"])
        assertEquals(JsonValue.text("medium"), ResponsesJson.field(wire.json["output_config"], "effort"))
        assertFalse(wire.json.containsKey("temperature"))
    }

    @Test
    fun manualGenerationKeepsBudgetThinkingPassthrough() {
        val wire = build("claude-sonnet-4-5-20250929", reasoning = ReasoningParameters(thinkingBudget = 2_048))
        assertEquals(
            JsonValue.obj("type" to JsonValue.text("enabled"), "budget_tokens" to JsonValue.integer(2_048)),
            wire.json["thinking"],
        )
        assertEquals(JsonValue.integer(2_048), wire.json["max_tokens"])
        assertTrue(wire.betaFeatures.isEmpty())
    }

    @Test
    fun interleavedBetaRidesManualThinkingWithToolsOnClaude4() {
        val wire = build(
            "claude-sonnet-4-5-20250929",
            reasoning = ReasoningParameters(thinkingBudget = 2_048),
            tools = listOf(ProviderTool("lookup", "find", "{\"type\":\"object\"}")),
        )
        assertEquals(listOf("interleaved-thinking-2025-05-14"), wire.betaFeatures)
        val adaptive = build(
            "claude-opus-4-8",
            tools = listOf(ProviderTool("lookup", "find", "{\"type\":\"object\"}")),
            options = """{"effort":"high"}""",
        )
        assertTrue(adaptive.betaFeatures.isEmpty())
        val legacyEffort = build("claude-sonnet-4-5-20250929", options = """{"effort":"high"}""")
        assertEquals(listOf("effort-2025-11-24"), legacyEffort.betaFeatures)
    }

    @Test
    fun adaptiveStripsSamplingWhileLegacyKeepsIt() {
        val adaptive = build("claude-opus-4-8", parameters = sampling(topP = 0.9))
        assertFalse(adaptive.json.containsKey("temperature"))
        assertFalse(adaptive.json.containsKey("top_p"))
        assertFalse(adaptive.json.containsKey("top_k"))
        val legacy = build(
            "claude-sonnet-4-5-20250929",
            parameters = sampling(topP = 0.9),
            reasoning = thinkingOff,
            isReasoning = false,
        )
        assertEquals(JsonValue.Number.raw("0.7"), legacy.json["temperature"])
        assertFalse(legacy.json.containsKey("top_p"))
        assertEquals(JsonValue.integer(40), legacy.json["top_k"])
    }

    @Test
    fun thinkingWithToolsForcesAutoToolChoice() {
        val wire = build(
            "claude-sonnet-4-5-20250929",
            reasoning = ReasoningParameters(thinkingBudget = 2_048),
            tools = listOf(ProviderTool("lookup_weather", "lookup", "{\"type\":\"object\",\"properties\":{}}")),
            options = """{"tool_choice":{"type":"function","function":{"name":"lookup_weather"}}}""",
        )
        assertEquals(JsonValue.obj("type" to JsonValue.text("auto")), wire.json["tool_choice"])
    }

    @Test
    fun structuredOutputRidesOutputConfigFormat() {
        val wire = build(
            "claude-sonnet-4-5-20250929",
            isReasoning = false,
            reasoning = thinkingOff,
            options = """{"response_format":{"type":"json_object"}}""",
        )
        assertEquals(
            JsonValue.obj("format" to JsonValue.obj("type" to JsonValue.text("json"))),
            wire.json["output_config"],
        )
    }

    @Test
    fun systemAndToolsGetTailCacheBreakpoints() {
        val wire = build(
            "claude-sonnet-5",
            messages = listOf(ProviderMessage(ProviderRole.SYSTEM, "stable"), ProviderMessage(ProviderRole.USER, "hi")),
            tools = listOf(
                ProviderTool("alpha_tool", "first", "{\"type\":\"object\"}"),
                ProviderTool("beta_tool", "second", "{\"type\":\"object\"}"),
            ),
        )
        assertEquals(anthropicEphemeral(), wire.json["cache_control"])
        val system = ResponsesJson.array(wire.json["system"])!!
        assertEquals(1, system.size)
        assertEquals(anthropicEphemeral(), (system.single() as JsonValue.Object)["cache_control"])
        val tools = ResponsesJson.array(wire.json["tools"])!!
        assertEquals(2, tools.size)
        assertFalse((tools[0] as JsonValue.Object).containsKey("cache_control"))
        assertEquals(anthropicEphemeral(), (tools[1] as JsonValue.Object)["cache_control"])
    }

    @Test
    fun overflowStripsTheEarliestToolMarkers() {
        val marked = (1..4).joinToString(",") { index ->
            """{"type":"function","cache_control":{"type":"ephemeral"},"function":{"name":"tool_$index","parameters":{"type":"object"}}}"""
        }
        val wire = build(
            "claude-sonnet-5",
            messages = listOf(ProviderMessage(ProviderRole.SYSTEM, "stable"), ProviderMessage(ProviderRole.USER, "hi")),
            options = """{"tools":[$marked]}""",
        )
        val tools = ResponsesJson.array(wire.json["tools"])!!
        assertEquals(4, tools.size)
        assertFalse((tools[0] as JsonValue.Object).containsKey("cache_control"))
        assertEquals(anthropicEphemeral(), (tools.last() as JsonValue.Object)["cache_control"])
    }

    @Test
    fun allInvalidToolsYieldNoToolsKey() {
        val wire = build(
            "claude-sonnet-5",
            messages = listOf(ProviderMessage(ProviderRole.SYSTEM, "stable"), ProviderMessage(ProviderRole.USER, "hi")),
            options = """{"tools":[{"type":"custom","function":{"name":"dropped","parameters":{"type":"object"}}}]}""",
        )
        assertFalse(wire.json.containsKey("tools"))
        assertEquals(anthropicEphemeral(), wire.json["cache_control"])
    }

    @Test
    fun imagesInlineThroughTheInjectedSourceAndMissesDrop() {
        val source = AnthropicImageSource { url ->
            if (url == "https://example.com/cat.png") AnthropicImageBytes("image/png", "AQID") else null
        }
        val urls = listOf("data:image/png;base64,QUJD", "https://example.com/cat.png", "https://example.com/missing.png")
        val sidecar = AnthropicRequestSidecar(imageSource = source, imageUrls = mapOf(0 to urls))
        val wire = build(
            "claude-sonnet-4-5-20250929",
            messages = listOf(ProviderMessage(ProviderRole.USER, "look")),
            sidecar = sidecar,
            isReasoning = false,
            reasoning = thinkingOff,
        )
        val firstMessage = ResponsesJson.array(wire.json["messages"])!!.first() as JsonValue.Object
        val content = ResponsesJson.array(firstMessage["content"])!!.map { it as JsonValue.Object }
        assertEquals("text", ResponsesJson.text(content[0]["type"]))
        assertEquals("image", ResponsesJson.text(content[1]["type"]))
        assertEquals("QUJD", ResponsesJson.text(ResponsesJson.field(content[1]["source"], "data")))
        assertEquals("AQID", ResponsesJson.text(ResponsesJson.field(content[2]["source"], "data")))
        assertEquals(3, content.size)
    }

    @Test
    fun assistantHistoryKeepsNullToolArguments() {
        val messages = listOf(
            ProviderMessage(
                ProviderRole.ASSISTANT,
                "",
                toolCallsJson = """[{"id":"call_1","type":"function","function":{"name":"lookup","arguments":"null"}}]""",
            ),
        )
        val wire = build(
            "claude-sonnet-4-5-20250929",
            messages = messages,
            isReasoning = false,
            reasoning = thinkingOff,
        )
        val firstMessage = ResponsesJson.array(wire.json["messages"])!!.first() as JsonValue.Object
        val blocks = ResponsesJson.array(firstMessage["content"])!!.map { it as JsonValue.Object }
        assertEquals(JsonValue.Null, blocks.single()["input"])
    }

    @Test
    fun baseUrlsResolveToSameOriginMessagesInThreeBranches() {
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicEndpoint.messagesUrl("https://api.anthropic.com/v1/messages"))
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicEndpoint.messagesUrl("https://api.anthropic.com/v1"))
        assertEquals("https://relay.fixture/v1/messages", AnthropicEndpoint.messagesUrl("https://relay.fixture"))
        assertEquals("https://gateway.fixture/claude/messages", AnthropicEndpoint.messagesUrl("https://gateway.fixture/claude/messages"))
    }

    @Test
    fun configuredHeadersCannotOverrideTheReservedProtocolHeaders() {
        val resolved =
            resolvedConfig(
                "claude-haiku-4-5",
                headers = listOf(ProviderHeader("x-api-key", "evil"), ProviderHeader("anthropic-version", "1999")),
            )
        val credential = ProviderCredential.fromUtf8(charArrayOf('s', 'k'))
        val headers = AnthropicRequestHeaders.byok(resolved, credential, emptyList())
        assertEquals("sk", headers.first { it.name == "x-api-key" }.value)
        assertEquals("2023-06-01", headers.first { it.name == "anthropic-version" }.value)
    }

    private val thinkingOff = ReasoningParameters(flags = ReasoningFlags(thinkingEnabled = false))

    private fun sampling(topP: Double? = null) = ModelParameters(maxOutputTokens = 2_048, temperature = 0.7, topP = topP, topK = 40)

    private fun build(
        model: String,
        messages: List<ProviderMessage> = listOf(ProviderMessage(ProviderRole.USER, "hi")),
        tools: List<ProviderTool> = emptyList(),
        reasoning: ReasoningParameters = ReasoningParameters(thinkingBudget = 1_024),
        parameters: ModelParameters = ModelParameters(maxOutputTokens = 2_048, temperature = 0.7),
        options: String? = null,
        sidecar: AnthropicRequestSidecar? = null,
        isReasoning: Boolean = true,
        baseUrl: String = "https://api.anthropic.com/v1",
    ): AnthropicWireBody {
        val effectiveParameters = options?.let { raw ->
            val extras = JsonValue.parseObject(raw).asMap().mapValues { (_, value) -> value.literal() }
            ModelParameters(
                maxOutputTokens = parameters.maxOutputTokens,
                temperature = parameters.temperature,
                topP = parameters.topP,
                topK = parameters.topK,
                typedExtraBody = extras,
            )
        } ?: parameters
        val request = resolvedRequest(model, messages, tools, effectiveParameters, reasoning, isReasoning, baseUrl)
        val scope = AnthropicHistoryScope.compute(request.resolved)
        return AnthropicRequestBody.build(request, sidecar, scope)
    }

    private fun resolvedRequest(
        model: String,
        messages: List<ProviderMessage>,
        tools: List<ProviderTool>,
        parameters: ModelParameters,
        reasoning: ReasoningParameters,
        isReasoning: Boolean,
        baseUrl: String,
    ): ProviderAdapterRequest {
        val resolved = resolvedConfig(model, baseUrl, parameters, reasoning, isReasoning)
        return ProviderAdapterRequest(resolved, messages, tools, null)
    }

    private fun resolvedConfig(
        model: String,
        baseUrl: String = "https://api.anthropic.com/v1",
        parameters: ModelParameters = ModelParameters(maxOutputTokens = 2_048, temperature = 0.7),
        reasoning: ReasoningParameters = ReasoningParameters(thinkingBudget = 1_024),
        isReasoning: Boolean = true,
        headers: List<ProviderHeader> = emptyList(),
    ): ResolvedProviderConfig {
        val vendor = VendorConfig(
            VendorIdentity("fixture-vendor", "anthropic"),
            VendorConnection(baseUrl, AuthMode.X_API_KEY, CredentialReference("fixture.key")),
            headers = headers,
        )
        val profile = ModelProfile(
            "fixture-profile",
            "fixture-vendor",
            model,
            providerScope = "anthropic",
            protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
            adapter = ModelAdapter.ANTHROPIC,
        )
        val runtime = ModelRuntime(
            reasoning = reasoning,
            parameters = parameters,
            features = ModelFeatures(reasoning = isReasoning),
        )
        val endpoint = if (baseUrl.endsWith("/messages")) baseUrl else "$baseUrl/messages"
        val route = ProviderRoute(baseUrl, endpoint, AuthMode.X_API_KEY, vendor.credential, emptyList(), "anthropic")
        return ResolvedProviderConfig(
            vendor,
            profile,
            runtime,
            "anthropic",
            "anthropic",
            ProviderProtocol.ANTHROPIC_MESSAGES,
            ProviderProtocolSource.DEFAULT,
            route,
        )
    }
}
