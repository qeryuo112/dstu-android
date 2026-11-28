package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderTool
import com.deepstudent.services.providers.api.ReasoningParameters
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class GoogleGeminiRequestTest {
    @Test
    fun `intermediate body maps thinking budget and api version`() {
        val resolved = resolved(
            model = "gemini-2.5-flash",
            reasoning = ReasoningParameters(thinkingBudget = 512, reasoningEffort = "medium"),
            thinkingEnabled = true,
            extraBody = mapOf("gemini_api_version" to "v1beta"),
        )
        val body = GoogleGeminiRequest(request(resolved), scope(resolved), null, null).body()
        assertEquals(
            JsonValue.obj("thinkingBudget" to JsonValue.integer(512), "includeThoughts" to JsonValue.bool(true)),
            body["thinkingConfig"],
        )
        assertEquals(JsonValue.text("v1beta"), body["gemini_api_version"])
        assertEquals(JsonValue.text("gemini-2.5-flash"), body["model"])
        assertEquals(JsonValue.bool(true), body["stream"])
    }

    @Test
    fun `disabled thinking on non-flash models omits the thinking map`() {
        val resolved = resolved(model = "gemini-2.2-pro", thinkingEnabled = false)
        val body = GoogleGeminiRequest(request(resolved), scope(resolved), null, null).body()
        assertNull(body["thinkingConfig"])
    }

    @Test
    fun `official gemini 3 effort levels snap through the frozen registry`() {
        val resolved = resolved(model = "gemini-3-pro", reasoning = ReasoningParameters(reasoningEffort = "xhigh"), thinkingEnabled = true)
        val body = GoogleGeminiRequest(request(resolved), scope(resolved), null, null).body()
        assertEquals(JsonValue.text("high"), body.at("thinkingConfig").get("thinkingLevel"))
        assertNull(body.at("thinkingConfig").get("thinkingBudget"))
    }

    @Test
    fun `gemini 3 flash disabled thinking uses the minimal level`() {
        val resolved = resolved(model = "gemini-3-flash", thinkingEnabled = false)
        val body = GoogleGeminiRequest(request(resolved), scope(resolved), null, null).body()
        assertEquals(JsonValue.text("minimal"), body.at("thinkingConfig").get("thinkingLevel"))
    }

    @Test
    fun `reasoning models keep max completion tokens and drop sampling`() {
        val resolved = resolved(model = "gemini-3-pro", reasoning = ReasoningParameters(reasoningEffort = "low"), thinkingEnabled = true)
        val body = GoogleGeminiRequest(request(resolved), scope(resolved), null, null).body()
        assertEquals(JsonValue.integer(1536), body["max_completion_tokens"])
        assertNull(body.atOrNull("temperature"))
    }

    @Test
    fun `tool declarations and auto choice enter the intermediate body`() {
        val resolved = resolved(model = "gemini-2.5-flash", thinkingEnabled = true)
        val tool = ProviderTool("fixture_lookup", "desc", """{"type":"object","properties":{"tags":{"type":"array"}}}""")
        val body = GoogleGeminiRequest(request(resolved, tools = listOf(tool)), scope(resolved), null, null).body()
        val tools = body.atArray("tools")
        assertEquals(1, tools.values.size)
        assertEquals(
            JsonValue.obj(
                "type" to JsonValue.text("function"),
                "function" to
                    JsonValue.obj(
                        "name" to JsonValue.text("fixture_lookup"),
                        "description" to JsonValue.text("desc"),
                        "parameters" to JsonValue.parse(tool.inputSchemaJson),
                    ),
            ),
            tools.values.first(),
        )
        assertEquals(JsonValue.text("auto"), body["tool_choice"])
    }

    @Test
    fun `converter forces v1beta for thinking and keeps explicit v1 without it`() {
        val thinkingBody = JsonValue.parseObject(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"stream":true,
               "thinkingConfig":{"thinkingBudget":-1}}""",
        )
        val thinking = GoogleGeminiConverter.build("gemini-2.5-flash", thinkingBody, route("v1"))
        assertEquals("v1beta", thinking.version)
        val plain = GoogleGeminiConverter.build("gemini-2.2-pro", plainBody(), route("v1"))
        assertEquals("v1", plain.version)
    }

    @Test
    fun `model prefix wins over the route version`() {
        val wire = GoogleGeminiConverter.build("v1beta:gemini-2.5-flash", plainBody(), route("v1"))
        assertEquals("v1beta", wire.version)
        assertEquals("gemini-2.5-flash", wire.model)
        assertTrue(wire.url.endsWith("/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse"))
        assertTrue(wire.url.startsWith("https://generativelanguage.example"))
    }

    @Test
    fun `unprotected function calls degrade to text for cross target history`() {
        val signed = JsonValue.parseObject(
            """{"model":"m","messages":[
               {"role":"user","content":"q"},
               {"role":"assistant","content":"","thought_signature":"sig","tool_calls":[
                  {"id":"fc-native-1","type":"function","function":{"name":"lookup","arguments":"{\"a\":1}"}}]},
               {"role":"tool","tool_call_id":"fc-native-1","content":"{\"ok\":true}"}],"stream":true}""",
        )
        val signedWire = GoogleGeminiConverter.build("gemini-3-pro", signed, route("v1beta"))
        val signedParts = signedWire.body.atArray("contents").values[1].asObject().atArray("parts").values
        assertEquals("fc-native-1", signedParts.first().asObject().at("functionCall").atText("id"))
        assertEquals(JsonValue.text("sig"), signedParts.first().asObject().get("thoughtSignature"))

        val stripped = JsonValue.parseObject(signed.literal().replace("\"thought_signature\":\"sig\",", ""))
        val degraded = GoogleGeminiConverter.build("gemini-3-pro", stripped, route("v1beta"))
        val contents = degraded.body.atArray("contents").values
        assertEquals(
            JsonValue.obj("text" to JsonValue.text("""[Tool call: lookup({"a":1})]""")),
            contents[1].asObject().atArray("parts").values.first(),
        )
        assertEquals(
            JsonValue.obj("text" to JsonValue.text("""[Tool result for lookup: {"ok":true}]""")),
            contents[2].asObject().atArray("parts").values.first(),
        )
    }

    @Test
    fun `synthetic tool call ids never go back to the api`() {
        val synthetic = "call-3b241101-e2bb-4255-8caf-4136c566a962"
        val body = JsonValue.parseObject(
            """{"model":"m","messages":[
               {"role":"user","content":"q"},
               {"role":"assistant","content":"","tool_calls":[
                  {"id":"$synthetic","type":"function","function":{"name":"lookup","arguments":"{}"}}]},
               {"role":"tool","tool_call_id":"$synthetic","content":"ok"}],"stream":true}""",
        )
        val wire = GoogleGeminiConverter.build("gemini-2.5-flash", body, route("v1beta"))
        val modelParts = wire.body.atArray("contents").values[1].asObject().atArray("parts").values
        assertNull(modelParts.first().asObject().atOrNull("functionCall")?.get("id"))
        val toolParts = wire.body.atArray("contents").values[2].asObject().atArray("parts").values
        assertNull(toolParts.first().asObject().atOrNull("functionResponse")?.get("id"))
        assertTrue(isSyntheticToolCallId(synthetic))
        assertTrue(!isSyntheticToolCallId("fc-native-1"))
    }

    @Test
    fun `tool results reverse look up the assistant tool name`() {
        val body = JsonValue.parseObject(
            """{"model":"m","messages":[
               {"role":"user","content":"q"},
               {"role":"assistant","content":"","thought_signature":"sig","tool_calls":[
                  {"id":"fc-7","type":"function","function":{"name":"lookup","arguments":"{}"}}]},
               {"role":"tool","tool_call_id":"fc-7","content":"plain"}],"stream":true}""",
        )
        val wire = GoogleGeminiConverter.build("gemini-3-pro", body, route("v1beta"))
        val response = wire.body.atArray("contents").values[2].asObject().atArray("parts").values.first().asObject().at("functionResponse")
        assertEquals(JsonValue.text("lookup"), response.get("name"))
        assertEquals(JsonValue.obj("result" to JsonValue.text("plain")), response.get("response"))
        assertEquals(JsonValue.text("fc-7"), response.get("id"))
    }

    @Test
    fun `data url images become inline data parts`() {
        val body = JsonValue.parseObject(
            """{"model":"m","messages":[{"role":"user","content":[
               {"type":"text","text":"看"},
               {"type":"image_url","image_url":{"url":"data:image/png;base64,QUJD"}}]}],"stream":true}""",
        )
        val wire = GoogleGeminiConverter.build("gemini-2.5-flash", body, route("v1beta"))
        val parts = wire.body.atArray("contents").values.first().asObject().atArray("parts").values
        assertEquals(
            JsonValue.obj("mimeType" to JsonValue.text("image/png"), "data" to JsonValue.text("QUJD")),
            parts[1].asObject().get("inlineData"),
        )
    }

    @Test
    fun `response format and stop sequences reach the generation config`() {
        val body = JsonValue.parseObject(
            """{"model":"m","messages":[{"role":"user","content":"q"}],"stream":true,
               "response_format":{"type":"json_schema","json_schema":{"name":"n","schema":{"type":"object"}}},
               "stop":["END"],"frequency_penalty":0.5}""",
        )
        val wire = GoogleGeminiConverter.build("gemini-2.2-pro", body, route("v1"))
        val config = wire.body.at("generationConfig")
        assertEquals(JsonValue.text("application/json"), config.get("responseMimeType"))
        assertEquals(JsonValue.obj("type" to JsonValue.text("object")), config.get("responseSchema"))
        assertEquals(JsonValue.array(listOf(JsonValue.text("END"))), config.get("stopSequences"))
        assertEquals(JsonValue.Number.raw("0.5"), config.get("frequencyPenalty"))
    }

    @Test
    fun `f32 shortest keeps the shortest round tripping decimal`() {
        assertEquals("0.25", geminiF32ShortestNumber(0.25).literal)
        assertEquals("1", geminiF32ShortestNumber(1.0).literal)
        assertEquals("0.7", geminiF32ShortestNumber(0.7).literal)
        assertEquals("0.12345679", geminiF32ShortestNumber(0.123456789).literal)
    }

    @Test
    fun `endpoint route recovery is strict and same origin`() {
        val route = GoogleGeminiEndpoint.route("https://host.example/v1/models/gemini-pro%3Ax:streamGenerateContent?alt=sse")
        assertEquals("https://host.example", route.origin)
        assertEquals("", route.rootPath)
        assertEquals("v1", route.version)
        for (bad in listOf(
            "http://host.example/v1/models/m:streamGenerateContent?alt=sse",
            "https://host.example/v1/models/m:generateContent?alt=sse",
            "https://host.example/v3/models/m:streamGenerateContent?alt=sse",
            "https://host.example/v1/models/m:streamGenerateContent?alt=sse&evil=1",
            "https://user:pass@host.example/v1/models/m:streamGenerateContent?alt=sse",
            "https://host.example/v1/models/m:streamGenerateContent",
        )) {
            runCatching { GoogleGeminiEndpoint.validate(bad, allowLoopback = false) }
                .onSuccess { error("accepted invalid endpoint $bad") }
        }
    }

    @Test
    fun `credential query is percent encoded and header safe values verified`() {
        assertEquals(
            "https://h/v1beta/models/m:streamGenerateContent?alt=sse&key=a%2Fb+c",
            GoogleGeminiEndpoint.withKey("https://h/v1beta/models/m:streamGenerateContent?alt=sse", "a/b c"),
        )
        assertEquals("https://h/x?alt=sse", GoogleGeminiEndpoint.withKey("https://h/x?alt=sse", null))
    }

    @Test
    fun `signature ports replay same target and drop mismatched scopes`() {
        val resolved = resolved(model = "gemini-3-pro", thinkingEnabled = true)
        val scope = GoogleGeminiScope(resolved)
        val same = GeminiSignedTurn("sig", scope.value)
        assertEquals(same, scope.replayable(same))
        assertNull(scope.replayable(GeminiSignedTurn("sig", "other")))
        // Legacy unscoped history keeps the desktop replay policy.
        assertEquals(GeminiSignedTurn("sig", null), scope.replayable(GeminiSignedTurn("sig", null)))
        assertNull(scope.replayable(null))
    }

    @Test
    fun `injected image parts ride along the user message`() {
        val resolved = resolved(model = "gemini-2.5-flash", thinkingEnabled = true)
        val images = GeminiImageContentSource { index ->
            if (index ==
                1
            ) {
                listOf(GeminiImagePart.Inline("image/jpeg", "REY="), GeminiImagePart.DataUrl("data:image/gif;base64,R0lG"))
            } else {
                emptyList()
            }
        }
        val messages = listOf(
            ProviderMessage(ProviderRole.SYSTEM, "sys"),
            ProviderMessage(ProviderRole.USER, "看图"),
        )
        val body = GoogleGeminiRequest(request(resolved, messages = messages), scope(resolved), null, images).body()
        val parts = body.atArray("messages").values[1].asObject().let { value ->
            checkNotNull(value["content"] as? JsonValue.Array).values
        }
        assertEquals(JsonValue.text("看图"), parts.first().asObject().get("text"))
        assertEquals("data:image/jpeg;base64,REY=", parts[1].asObject().at("image_url").atText("url"))
        assertEquals("data:image/gif;base64,R0lG", parts[2].asObject().at("image_url").atText("url"))
    }

    private fun plainBody(): JsonValue.Object = JsonValue.parseObject(
        """{"model":"m","messages":[{"role":"user","content":"hi"}],"stream":true}""",
    )

    private fun route(version: String) = GoogleGeminiRoute("https://generativelanguage.example", "", version)

    private fun resolved(
        model: String,
        reasoning: ReasoningParameters = ReasoningParameters(),
        thinkingEnabled: Boolean = true,
        extraBody: Map<String, String> = emptyMap(),
    ): ResolvedProviderConfig = GoogleGeminiFixtures.resolved(model, reasoning, thinkingEnabled, extraBody)

    private fun request(
        resolved: ResolvedProviderConfig,
        tools: List<ProviderTool> = emptyList(),
        messages: List<ProviderMessage> = listOf(ProviderMessage(ProviderRole.USER, "hi")),
    ): ProviderAdapterRequest = ProviderAdapterRequest(resolved, messages, tools, null)

    private fun scope(resolved: ResolvedProviderConfig) = GoogleGeminiScope(resolved)
}

private fun JsonValue.at(key: String): JsonValue.Object = (this as? JsonValue.Object)?.let { it[key] as? JsonValue.Object }
    ?: error("missing object $key in $this")

private fun JsonValue.atOrNull(key: String): JsonValue.Object? = (this as? JsonValue.Object)?.let { it[key] as? JsonValue.Object }

private fun JsonValue.atArray(key: String): JsonValue.Array =
    (this as? JsonValue.Object)?.let { it[key] as? JsonValue.Array } ?: error("missing array $key in $this")

private fun JsonValue.atText(key: String): String? = (this as? JsonValue.Object)?.let { it[key] as? JsonValue.Text }?.value

private fun JsonValue.atArrayValue(): JsonValue.Array = this as? JsonValue.Array ?: error("not an array: $this")

private fun JsonValue.asObject(): JsonValue.Object = this as? JsonValue.Object ?: error("not an object: $this")
