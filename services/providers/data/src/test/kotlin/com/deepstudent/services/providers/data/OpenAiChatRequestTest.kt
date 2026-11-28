package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ModelParameters
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderTool
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatRequestTest {
    @Test
    fun `builds one-turn request with streaming usage`() {
        val body = OpenAiChatRequest.body(
            "gpt-test",
            listOf(
                ProviderMessage(ProviderRole.SYSTEM, "rules"),
                ProviderMessage(ProviderRole.USER, "你好"),
            ),
        )
        assertEquals("gpt-test", (body["model"] as JsonValue.Text).value)
        assertEquals(true, (body["stream"] as JsonValue.Bool).value)
        val options = body["stream_options"] as JsonValue.Object
        assertEquals(true, (options["include_usage"] as JsonValue.Bool).value)
        val messages = (body["messages"] as JsonValue.Array).values
        assertEquals(2, messages.size)
        assertEquals("user", ((messages[1] as JsonValue.Object)["role"] as JsonValue.Text).value)
    }

    @Test
    fun `rejects empty model and emits tool role wire fields`() {
        runCatching { OpenAiChatRequest.body("", listOf(ProviderMessage(ProviderRole.USER, "x"))) }
            .onSuccess { error("accepted empty model") }
            .onFailure { failure -> assertTrue(failure is IllegalArgumentException) }
        val body = OpenAiChatRequest.body(
            "model",
            listOf(ProviderMessage(ProviderRole.TOOL, "result", name = "lookup", toolCallId = "call_1")),
        )
        val message = (body["messages"] as JsonValue.Array).values.single() as JsonValue.Object
        assertEquals("tool", (message["role"] as JsonValue.Text).value)
        assertEquals("lookup", (message["name"] as JsonValue.Text).value)
        assertEquals("call_1", (message["tool_call_id"] as JsonValue.Text).value)
    }

    @Test
    fun `typed extras are preserved but cannot replace the model2 tool choice`() {
        val setup = resolved(
            provider = "custom",
            adapterId = "general",
            extra = mapOf("tool_choice" to "\"required\"", "custom_null" to "null"),
            typedExtra = mapOf("custom_quote" to "\"true\""),
        )
        val tools = listOf(ProviderTool("lookup", "Lookup", """{"properties":{"q":{"type":"string"}}}"""))
        val body = OpenAiChatRequestPlanner.plan(
            ProviderAdapterRequest(setup, listOf(ProviderMessage(ProviderRole.USER, "hello")), tools, "s"),
        ).body
        assertEquals("auto", body.text("tool_choice"))
        assertEquals("true", body.text("custom_quote"))
        assertTrue(body.containsKey("custom_null"))
        assertEquals(JsonValue.Null, body["custom_null"])
        val parameters = ((body["tools"] as JsonValue.Array).values.single() as JsonValue.Object)["function"] as JsonValue.Object
        val schema = parameters["parameters"] as JsonValue.Object
        assertEquals("object", schema.text("type"))
    }

    @Test
    fun `qwen tool history strips choice and minimax uses reasoning details`() {
        val qwenSetup = resolved(provider = "qwen", adapterId = "qwen", model = "qwen3.7-plus")
        val qwen = OpenAiChatRequestPlanner.plan(
            ProviderAdapterRequest(
                qwenSetup,
                listOf(
                    ProviderMessage(ProviderRole.USER, "hello"),
                    ProviderMessage(ProviderRole.TOOL, "result", toolCallId = "call_1"),
                ),
                listOf(ProviderTool("lookup", "Lookup", "{}")),
                "s",
            ),
        ).body
        assertTrue(qwen.containsKey("tools"))
        assertTrue(!qwen.containsKey("tool_choice"))

        val minimaxSetup = resolved(provider = "minimax", adapterId = "minimax")
        val minimax = OpenAiChatRequestPlanner.plan(
            ProviderAdapterRequest(
                minimaxSetup,
                listOf(ProviderMessage(ProviderRole.ASSISTANT, "done", reasoningContent = "thought")),
                emptyList(),
                "s",
            ),
        ).body
        val message = (minimax["messages"] as JsonValue.Array).values.single() as JsonValue.Object
        val details = (message["reasoning_details"] as JsonValue.Array).values.single() as JsonValue.Object
        assertEquals("thought", (details["text"] as JsonValue.Text).value)
    }

    @Test
    fun `official openai gets usage stream option while compatible hosts do not`() {
        val official = resolved(provider = "openai", adapterId = "general", baseUrl = "https://api.openai.com/v1")
        val compatible = resolved(provider = "siliconflow", adapterId = "general", baseUrl = "https://api.siliconflow.cn/v1")
        val officialBody = OpenAiChatRequestPlanner.plan(
            ProviderAdapterRequest(official, listOf(ProviderMessage(ProviderRole.USER, "x")), emptyList(), "s"),
        ).body
        val compatibleBody = OpenAiChatRequestPlanner.plan(
            ProviderAdapterRequest(compatible, listOf(ProviderMessage(ProviderRole.USER, "x")), emptyList(), "s"),
        ).body
        assertEquals(true, ((officialBody["stream_options"] as JsonValue.Object)["include_usage"] as JsonValue.Bool).value)
        assertTrue(!compatibleBody.containsKey("stream_options"))
    }

    @Test
    fun `usage inclusion checks exact host and preserves explicit option`() {
        fun planned(baseUrl: String, typedExtra: Map<String, String> = emptyMap()): JsonValue.Object {
            val config = resolved(provider = "openai", adapterId = "general", baseUrl = baseUrl, typedExtra = typedExtra)
            return OpenAiChatRequestPlanner.plan(
                ProviderAdapterRequest(config, listOf(ProviderMessage(ProviderRole.USER, "x")), emptyList(), null),
            ).body
        }
        assertTrue(!planned("https://mirror.openai.com/v1").containsKey("stream_options"))
        val explicit = """{"include_usage":false}"""
        val actual = planned("https://api.openai.com/v1", mapOf("stream_options" to explicit))["stream_options"]
        assertEquals(JsonValue.parseObject(explicit), actual)
    }

    private fun resolved(
        provider: String,
        adapterId: String,
        baseUrl: String = "https://provider.example/v1",
        extra: Map<String, String> = emptyMap(),
        typedExtra: Map<String, String> = emptyMap(),
        model: String = "test-model",
    ): ResolvedProviderConfig {
        val vendor = com.deepstudent.services.providers.api.VendorConfig(
            com.deepstudent.services.providers.api.VendorIdentity("vendor", provider, provider),
            com.deepstudent.services.providers.api.VendorConnection(baseUrl),
        )
        return ResolvedProviderConfig(
            vendor = vendor,
            profile = com.deepstudent.services.providers.api.ModelProfile("test-model", vendor.id, model),
            runtime = com.deepstudent.services.providers.api.ModelRuntime(
                capabilities = com.deepstudent.services.providers.api.ModelCapabilities(tools = true),
                features = com.deepstudent.services.providers.api.ModelFeatures(reasoning = true),
                parameters = ModelParameters(maxOutputTokens = 2048, typedExtraBody = typedExtra, extraBody = extra),
            ),
            providerType = provider,
            providerScope = provider,
            protocol = com.deepstudent.services.providers.api.ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            protocolSource = com.deepstudent.services.providers.api.ProviderProtocolSource.MANUAL,
            route = com.deepstudent.services.providers.api.ProviderRoute(
                baseUrl = baseUrl,
                endpoint = baseUrl + "/chat/completions",
                authMode = com.deepstudent.services.providers.api.AuthMode.NONE,
                credential = null,
                headers = emptyList(),
                adapterId = adapterId,
            ),
        )
    }

    private fun JsonValue.Object.text(key: String): String? = (this[key] as? JsonValue.Text)?.value
}
