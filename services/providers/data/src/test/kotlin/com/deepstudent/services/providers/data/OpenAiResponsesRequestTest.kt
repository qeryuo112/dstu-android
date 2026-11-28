package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ModelCapabilities
import com.deepstudent.services.providers.api.ModelFeatures
import com.deepstudent.services.providers.api.ModelParameters
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ModelRuntime
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderProtocolSource
import com.deepstudent.services.providers.api.ProviderResponsesHistory
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

/**
 * Request-only checks for the Responses encoder; the eleven frozen oracle
 * cases run end-to-end through the app replay suite.
 */
class OpenAiResponsesRequestTest {
    @Test
    fun `roles and parts compile to ordered items with raw scoped history`() {
        val reasoning = """{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"thought"}]}"""
        val search = """{"type":"web_search_call","id":"ws_1","status":"completed"}"""
        val request = ProviderAdapterRequest(
            resolved(),
            listOf(
                ProviderMessage(ProviderRole.SYSTEM, "Fixture instructions."),
                ProviderMessage(
                    ProviderRole.USER,
                    "",
                    responses = ProviderResponsesHistory(
                        contentPartsJson = """
                            [{"type":"text","text":"协议回放"},{"type":"input_image","image_url":"data:image/png;base64,AAA","detail":"low"}]
                        """.trimIndent(),
                    ),
                ),
                ProviderMessage(
                    ProviderRole.ASSISTANT,
                    "",
                    toolCallsJson = """[{"type":"function","id":"call_1","function":{"name":"lookup","arguments":"{\"q\":\"1\"}"}}]""",
                    reasoningContent = "hidden for non official deepseek",
                    responses = ProviderResponsesHistory(reasoningItemJson = reasoning, webSearchItemsJson = "[$search]"),
                ),
                ProviderMessage(
                    ProviderRole.TOOL,
                    "",
                    toolCallId = "call_1",
                    responses = ProviderResponsesHistory(contentPartsJson = """[{"type":"text","text":"a"},{"type":"text","text":"b"}]"""),
                ),
                ProviderMessage(ProviderRole.USER, "Continue"),
            ),
            listOf(ProviderTool("lookup", "Lookup", """{"type":"object","properties":{"q":{"type":"string"}}}""", strict = true)),
            "session-1",
        )
        val body = OpenAiResponsesRequest.build(request).json
        assertEquals("Fixture instructions.", body.text("instructions"))
        val input = (body["input"] as JsonValue.Array).values.map { it as JsonValue.Object }
        assertEquals(
            listOf("user-role", "reasoning", "web_search_call", "function_call", "function_call_output", "user-role"),
            input.map(::itemKind),
        )
        assertEquals(JsonValue.parse(reasoning), input[1])
        assertEquals(JsonValue.parse(search), input[2])
        val parts = (input[0]["content"] as JsonValue.Array).values.map { it as JsonValue.Object }
        assertEquals("input_text", parts[0].text("type"))
        assertEquals("low", parts[1].text("detail"))
        assertEquals("a\nb", (input[4]["output"] as JsonValue.Text).value)
        assertEquals("session-1", body.text("prompt_cache_key"))
    }

    @Test
    fun `official openai gpt-5_6 uses the developer breakpoint and defaults include`() {
        val body = OpenAiResponsesRequest.build(
            ProviderAdapterRequest(
                resolved(baseUrl = "https://api.openai.com/v1"),
                listOf(ProviderMessage(ProviderRole.SYSTEM, "You are helpful."), ProviderMessage(ProviderRole.USER, "hi")),
                emptyList(),
                null,
            ),
        ).json
        val input = (body["input"] as JsonValue.Array).values.map { it as JsonValue.Object }
        assertEquals("developer", input.first().text("role"))
        assertEquals(
            JsonValue.obj("mode" to JsonValue.text("explicit")),
            ((input.first()["content"] as JsonValue.Array).values.single() as JsonValue.Object)["prompt_cache_breakpoint"],
        )
        assertFalse(body.containsKey("instructions"))
        assertEquals(
            listOf<JsonValue>(JsonValue.text("reasoning.encrypted_content")),
            (body["include"] as JsonValue.Array).values,
        )
        assertEquals(JsonValue.text("deep-student-background"), body["prompt_cache_key"])
    }

    @Test
    fun `codex restricted body strips knobs merges include and falls back on blank sessions`() {
        val body = OpenAiResponsesRequest.build(
            ProviderAdapterRequest(
                resolved(authMode = AuthMode.OPENAI_CODEX_OAUTH),
                listOf(ProviderMessage(ProviderRole.USER, "hi")),
                listOf(ProviderTool("lookup", "Lookup", "{}")),
                "  ",
            ),
        ).json
        assertEquals(false, (body["store"] as JsonValue.Bool).value)
        assertEquals(true, (body["stream"] as JsonValue.Bool).value)
        assertEquals(
            listOf<JsonValue>(JsonValue.text("reasoning.encrypted_content")),
            (body["include"] as JsonValue.Array).values,
        )
        assertFalse(body.containsKey("max_output_tokens"))
        assertFalse(body.containsKey("temperature"))
        assertEquals("deep-student-background", body.text("prompt_cache_key"))
        assertEquals("auto", body.text("tool_choice"))
        assertEquals(
            listOf("model", "input", "stream", "store", "reasoning", "tool_choice", "include", "tools", "prompt_cache_key"),
            body.keys(),
        )
    }

    @Test
    fun `official deepseek flash keeps official dialect and flags moderation with web tools`() {
        val typedExtra = mapOf(
            "safety_check" to """{"level":"high"}""",
            "title" to "\"synthetic title\"",
            "tools" to """[{"type":"deepseek_web_search"},{"type":"deepseek_read_url"}]""",
        )
        val request = ProviderAdapterRequest(
            resolved(
                provider = "deepseek",
                adapterId = "deepseek",
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-flash",
                typedExtra = typedExtra,
                topP = 0.75,
            ),
            listOf(
                ProviderMessage(ProviderRole.SYSTEM, "Fixture instructions."),
                ProviderMessage(
                    ProviderRole.ASSISTANT,
                    "",
                    reasoningContent = "prior reasoning",
                    toolCallsJson = """[{"type":"function","id":"call_1","function":{"name":"lookup","arguments":"{}"}}]""",
                ),
            ),
            listOf(ProviderTool("lookup", "Lookup", "{}")),
            "session-1",
        )
        val wire = OpenAiResponsesRequest.build(request)
        val body = wire.json
        assertEquals(JsonValue.obj("effort" to JsonValue.text("high")), body["reasoning"])
        assertEquals(0.95, (body["top_p"] as JsonValue.Number).literal.toDouble(), 1e-9)
        assertFalse(body.containsKey("temperature"))
        assertEquals("synthetic title", body.text("title"))
        assertFalse(body.containsKey("prompt_cache_key"))
        val tools = (body["tools"] as JsonValue.Array).values.map { it as JsonValue.Object }
        assertEquals(listOf("function", "deepseek_web_search", "deepseek_read_url"), tools.map { it.text("type") })
        assertEquals("high", ((body["safety_check"] as JsonValue.Object)["level"] as JsonValue.Text).value)
        assertTrue(wire.deepseekModerated)
        assertTrue(wire.deepseekWebTools)
        val reasoning = (body["input"] as JsonValue.Array).values.map { it as JsonValue.Object }.first()
        assertEquals("reasoning_text", ((reasoning["content"] as JsonValue.Array).values.single() as JsonValue.Object).text("type"))
    }

    @Test
    fun `grok non-reasoning models keep temperature only and drop reasoning`() {
        val body = OpenAiResponsesRequest.build(
            ProviderAdapterRequest(
                resolved(provider = "xai", adapterId = "grok", model = "grok-4-fast-non-reasoning", featuresReasoning = false),
                listOf(ProviderMessage(ProviderRole.SYSTEM, "Fixture instructions."), ProviderMessage(ProviderRole.USER, "hi")),
                emptyList(),
                "session-1",
            ),
        ).json
        assertEquals(0.7, (body["temperature"] as JsonValue.Number).literal.toDouble(), 1e-9)
        assertFalse(body.containsKey("top_p"))
        assertFalse(body.containsKey("reasoning"))
        assertFalse(body.containsKey("frequency_penalty"))
        assertFalse(body.containsKey("presence_penalty"))
        assertFalse(body.containsKey("reasoning_effort"))
        assertEquals("session-1", body.text("prompt_cache_key"))
    }

    @Test
    fun `blank names and duplicate tools are sanitized before conversion`() {
        val body = OpenAiResponsesRequest.build(
            ProviderAdapterRequest(
                resolved(),
                listOf(ProviderMessage(ProviderRole.USER, "hi")),
                listOf(
                    ProviderTool("lookup", "Lookup", "{}", strict = true),
                    ProviderTool("lookup", "Duplicate", "{}"),
                ),
                "session-1",
            ),
        ).json
        val tools = (body["tools"] as JsonValue.Array).values.map { it as JsonValue.Object }
        assertEquals(1, tools.size)
        assertEquals("function", tools.single().text("type"))
        assertEquals("lookup", tools.single().text("name"))
        assertEquals(true, (tools.single()["strict"] as JsonValue.Bool).value)
    }

    @Test
    fun `codex session ids keep trimmed values and the frozen background key`() {
        assertEquals("session-1", codexSessionId(" session-1 "))
        assertEquals("deep-student-background", codexSessionId(null))
        assertEquals("deep-student-background", codexSessionId("   "))
    }

    private fun itemKind(item: JsonValue.Object): String = when (val role = item.text("role")) {
        null -> item.text("type") ?: "unknown"
        else -> if (item.text("type") == null) "$role-role" else item.text("type") ?: "unknown"
    }

    private fun resolved(
        provider: String = "openai",
        adapterId: String = "general",
        baseUrl: String = "https://provider.example/v1",
        model: String = "gpt-5.6-fixture",
        authMode: AuthMode = AuthMode.BEARER_API_KEY,
        typedExtra: Map<String, String> = emptyMap(),
        topP: Double? = null,
        featuresReasoning: Boolean = true,
        reasoning: ReasoningParameters = ReasoningParameters(reasoningEffort = "high"),
    ): ResolvedProviderConfig {
        val vendor = VendorConfig(VendorIdentity("vendor", provider, provider), VendorConnection(baseUrl, authMode))
        return ResolvedProviderConfig(
            vendor = vendor,
            profile = ModelProfile("test-model", vendor.id, model),
            runtime = ModelRuntime(
                capabilities = ModelCapabilities(tools = true, images = true),
                features = ModelFeatures(reasoning = featuresReasoning),
                reasoning = reasoning,
                parameters = ModelParameters(maxOutputTokens = 2048, topP = topP, typedExtraBody = typedExtra),
                maxTokensLimit = 1536,
            ),
            providerType = provider,
            providerScope = provider,
            protocol = ProviderProtocol.OPENAI_RESPONSES,
            protocolSource = ProviderProtocolSource.MANUAL,
            route = ProviderRoute(
                baseUrl = baseUrl,
                endpoint = baseUrl + "/responses",
                authMode = authMode,
                credential = null,
                headers = emptyList(),
                adapterId = adapterId,
            ),
        )
    }

    private fun JsonValue.Object.text(key: String): String? = (this[key] as? JsonValue.Text)?.value
}
