package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleGeminiStreamDecoderTest {
    @Test
    fun `thought parts reasoning text and stop completion arrive in wire order`() {
        val decoder = GoogleGeminiStreamDecoder()
        val events = decoder.accept(
            """{"candidates":[{"content":{"parts":[{"thought":true,"text":"思考"},{"text":"回答"}]},
               "finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2,
               "totalTokenCount":5,"cachedContentTokenCount":1,"thoughtsTokenCount":2}}""",
        )
        val usage = events.filterIsInstance<GoogleGeminiStreamEvent.Usage>().single()
        assertEquals(3L, usage.prompt)
        assertEquals(2L, usage.completion)
        assertEquals(5L, usage.total)
        assertEquals(2L, usage.thoughts)
        assertEquals(1L, usage.cached)
        assertEquals(
            listOf<GoogleGeminiStreamEvent>(
                GoogleGeminiStreamEvent.Reasoning("思考"),
                GoogleGeminiStreamEvent.Text("回答"),
                GoogleGeminiStreamEvent.Done,
            ),
            events - usage,
        )
        assertTrue(decoder.terminal)
    }

    @Test
    fun `bare ndjson wrapped data and blank lines are tolerated`() {
        val decoder = GoogleGeminiStreamDecoder()
        assertEquals(
            listOf(GoogleGeminiStreamEvent.Text("NDJSON")),
            decoder.accept("""{"candidates":[{"content":{"parts":[{"text":"NDJSON"}]}}]}"""),
        )
        assertEquals(
            listOf(GoogleGeminiStreamEvent.Text("wrapped")),
            decoder.accept("""{"candidates":[{"content":{"parts":[{"text":"wrapped"}]}}]}"""),
        )
        assertEquals(emptyList<GoogleGeminiStreamEvent>(), decoder.accept("   "))
        assertEquals(emptyList<GoogleGeminiStreamEvent>(), decoder.accept("not json"))
    }

    @Test
    fun `done marker clears pending calls and becomes terminal`() {
        val decoder = GoogleGeminiStreamDecoder()
        toolCall(decoder, """{"name":"f","args":{}}""")
        assertEquals(listOf<GoogleGeminiStreamEvent>(GoogleGeminiStreamEvent.Done), decoder.accept("[DONE]"))
        assertTrue(decoder.terminal)
        assertEquals(emptyList<GoogleGeminiStreamEvent>(), decoder.accept("""{"candidates":[]}"""))
    }

    @Test
    fun `native ids keep a stable index while unnamed calls stay distinct`() {
        val decoder = GoogleGeminiStreamDecoder()
        val first = toolCall(decoder, """{"id":"fc-1","name":"a","args":{"x":1}}""")
        val second = toolCall(decoder, """{"name":"b","args":{}}""")
        val replay = toolCall(decoder, """{"id":"fc-1","name":"a","args":{"x":1}}""")
        assertEquals(GoogleGeminiStreamEvent.ToolCall(0, "fc-1", "a", """{"x":1}"""), first)
        assertEquals(1, second.index)
        assertTrue(second.id.startsWith("call-"))
        assertTrue(isSyntheticToolCallId(second.id))
        assertEquals(0, replay.index)
        assertEquals("fc-1", replay.id)
        assertEquals("""{"x":1}""", replay.argumentsSnapshot)
    }

    @Test
    fun `a late native id creates its own entry`() {
        val decoder = GoogleGeminiStreamDecoder()
        toolCall(decoder, """{"name":"f","args":{}}""")
        val late = toolCall(decoder, """{"id":"fc-late","name":"f","args":{"late":true}}""")
        assertEquals(1, late.index)
        assertEquals("fc-late", late.id)
    }

    @Test
    fun `thought signature events surface in part order`() {
        val decoder = GoogleGeminiStreamDecoder()
        val events = decoder.accept(
            """{"candidates":[{"content":{"parts":[{"text":"a"},{"thoughtSignature":"sig-1"},
               {"functionCall":{"id":"fc-2","name":"f","args":{}}}]},"thoughtSignature":"sig-candidate"}]}""",
        )
        assertEquals(
            listOf<GoogleGeminiStreamEvent>(
                GoogleGeminiStreamEvent.Text("a"),
                GoogleGeminiStreamEvent.Signature("sig-1"),
                GoogleGeminiStreamEvent.ToolCall(0, "fc-2", "f", "{}"),
                GoogleGeminiStreamEvent.Signature("sig-candidate"),
            ),
            events,
        )
    }

    @Test
    fun `thought marker variants and candidate payload thoughts are reasoning`() {
        val decoder = GoogleGeminiStreamDecoder()
        val events = decoder.accept(
            """{"candidates":[{"content":{"parts":[
               {"metadata":{"type":"Thought"},"text":"meta"},
               {"kind":"Thought","text":"kind"},
               {"type":"thought","text":"typed"},
               {"thought":{"value":false},"text":"not-thought"}]},
               "thoughts":[{"text":"candidate"},{"content":"alias"},"bare"]}],
               "thoughts":["payload"]}""",
        )
        val reasoning = events.filterIsInstance<GoogleGeminiStreamEvent.Reasoning>().map { it.value }
        assertEquals(listOf("meta", "kind", "typed", "candidate", "alias", "bare", "payload"), reasoning)
        assertEquals(listOf("not-thought"), events.filterIsInstance<GoogleGeminiStreamEvent.Text>().map { it.value })
    }

    @Test
    fun `delta parts use the strict thought flag and skip signature handling`() {
        val decoder = GoogleGeminiStreamDecoder()
        val events = decoder.accept(
            """{"candidates":[{"content":{"parts":[{"text":"main"}]},
               "delta":{"parts":[{"thought":{"value":false},"text":"not-thought"},
               {"thought":"yes","text":"ignored-thought"},
               {"thought":true,"text":"delta-thought","thoughtSignature":"skipped"}]}}]}""",
        )
        assertEquals(
            listOf("main", "not-thought", "ignored-thought"),
            events.filterIsInstance<GoogleGeminiStreamEvent.Text>().map {
                it.value
            },
        )
        assertEquals(listOf("delta-thought"), events.filterIsInstance<GoogleGeminiStreamEvent.Reasoning>().map { it.value })
        assertEquals(emptyList<GoogleGeminiStreamEvent.Signature>(), events.filterIsInstance<GoogleGeminiStreamEvent.Signature>())
    }

    @Test
    fun `prompt feedback and non stop finish reasons block with fixed types`() {
        val blocked = GoogleGeminiStreamDecoder().accept("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        assertEquals(listOf(GoogleGeminiStreamEvent.Blocked("prompt_blocked", "SAFETY")), blocked)

        val finish = GoogleGeminiStreamDecoder().accept(
            """{"candidates":[{"finishReason":"SAFETY","safetyRatings":[{"category":"c"}]}]}""",
        )
        assertEquals(listOf(GoogleGeminiStreamEvent.Blocked("content_blocked", "SAFETY")), finish)

        val maxTokens = GoogleGeminiStreamDecoder().accept("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""")
        assertEquals(listOf(GoogleGeminiStreamEvent.Blocked("provider_error", "MAX_TOKENS")), maxTokens)
    }

    @Test
    fun `usage falls back to the sum and lifts cached and thinking tokens`() {
        val missing = GoogleGeminiStreamDecoder().accept(
            """{"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":4}}""",
        ).single() as GoogleGeminiStreamEvent.Usage
        assertEquals(11L, missing.total)
        assertNull(missing.cached)
        assertNull(missing.thoughts)

        val aliased = GoogleGeminiStreamDecoder().accept(
            """{"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2,
               "cacheReadInputTokens":9,"thoughtsTokenCount":3}}""",
        ).single() as GoogleGeminiStreamEvent.Usage
        assertEquals(9L, aliased.cached)
        assertEquals(3L, aliased.thoughts)
    }

    @Test
    fun `non integer usage fields fall back like the frozen i64 reads`() {
        val usage = GoogleGeminiStreamDecoder().accept(
            """{"usageMetadata":{"promptTokenCount":1.5,"candidatesTokenCount":4,"totalTokenCount":9}}""",
        ).single() as GoogleGeminiStreamEvent.Usage
        assertEquals(0L, usage.prompt)
        assertEquals(4L, usage.completion)
        assertEquals(9L, usage.total)
    }

    private fun toolCall(decoder: GoogleGeminiStreamDecoder, call: String): GoogleGeminiStreamEvent.ToolCall {
        val events = decoder.accept("""{"candidates":[{"content":{"parts":[{"functionCall":$call}]}}]}""")
        return events.single() as GoogleGeminiStreamEvent.ToolCall
    }
}
