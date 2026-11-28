package com.deepstudent.services.providers.data

import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatDecoderTest {
    @Test
    fun `decodes text reasoning finish and exact non-negative usage`() {
        val decoder = OpenAiChatDecoder()
        val events = decoder.decode(
            """{"choices":[{"delta":{"content":"你好","reasoning_content":"想"},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18,""" +
                """"prompt_tokens_details":{"cached_tokens":3},""" +
                """"completion_tokens_details":{"reasoning_tokens":2}}}""",
        )
        assertEquals(
            listOf(
                OpenAiDecodeEvent.TextDelta("你好"),
                OpenAiDecodeEvent.ReasoningDelta("想"),
                OpenAiDecodeEvent.FinishReason("stop"),
                OpenAiDecodeEvent.UsageReported(
                    ProviderUsage(
                        inputTokens = 11,
                        cachedInputTokens = 3,
                        outputTokens = 7,
                        reasoningTokens = 2,
                        totalTokens = 18,
                    ),
                ),
            ),
            events,
        )
        assertTrue(decoder.frameHasPayload())
        assertTrue(decoder.allChoicesFinished())
    }

    @Test
    fun `reports done as explicit completion`() {
        val decoder = OpenAiChatDecoder()
        assertEquals(listOf(OpenAiDecodeEvent.Done), decoder.decode("[DONE]"))
        assertFalse(decoder.frameHasPayload())
    }

    @Test
    fun `rejects content after done`() {
        val decoder = OpenAiChatDecoder()
        decoder.decode("[DONE]")
        runCatching {
            decoder.decode("""{"choices":[{"delta":{"content":"late"},"finish_reason":"stop"}]}""")
        }.onSuccess { error("accepted content after DONE") }
            .onFailure { failure -> assertTrue(failure is ProviderException) }
    }

    @Test
    fun `rejects malformed frame and negative or fractional usage`() {
        for (payload in listOf(
            "data: {",
            """{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}],"usage":{"prompt_tokens":-1}}""",
            """{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1.5}}""",
        )) {
            runCatching { OpenAiChatDecoder().decode(payload) }
                .onSuccess { error("accepted invalid provider frame") }
                .onFailure { failure -> assertTrue(failure is ProviderException) }
        }
    }

    @Test
    fun `surfaces provider error without treating it as completion`() {
        assertEquals(
            listOf(OpenAiDecodeEvent.ProviderError("rate_limit", "slow down")),
            OpenAiChatDecoder().decode(
                """{"error":{"code":"rate_limit","message":"slow down"}}""",
            ),
        )
        assertEquals(
            listOf(OpenAiDecodeEvent.TextDelta("ok")),
            OpenAiChatDecoder().decode("""{"error":null,"choices":[{"delta":{"content":"ok"}}]}"""),
        )
    }

    @Test
    fun `decodes content parts reasoning variants tool deltas and cache usage`() {
        val events = OpenAiChatDecoder().decode(
            """{"choices":[{"delta":{"content":[{"type":"thinking","thinking":"plan"},{"type":"text","text":"answer"}],""" +
                """"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"lookup","arguments":{"q":"frag"}}}]}}],""" +
                """"usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17,""" +
                """"prompt_tokens_details":{"cached_tokens":4,"cache_write_tokens":2},""" +
                """"completion_tokens_details":{"reasoning_tokens":1}}}""",
        )
        assertEquals(
            listOf(
                OpenAiDecodeEvent.ReasoningDelta("plan"),
                OpenAiDecodeEvent.TextDelta("answer"),
                OpenAiDecodeEvent.ToolCallDelta(
                    OpenAiToolCallDelta(
                        0,
                        "call_1",
                        "function",
                        "lookup",
                        argumentsFragment = """{"q":"frag"}""",
                        rawJson = """{"index":0,"id":"call_1","type":"function","function":{"name":"lookup","arguments":{"q":"frag"}}}""",
                    ),
                ),
                OpenAiDecodeEvent.UsageReported(
                    ProviderUsage(
                        inputTokens = 12,
                        cachedInputTokens = 4,
                        cacheWriteInputTokens = 2,
                        outputTokens = 5,
                        reasoningTokens = 1,
                        totalTokens = 17,
                    ),
                ),
            ),
            events,
        )
    }

    @Test
    fun `does not collapse repeated or prefix shaped content fragments`() {
        val decoder = OpenAiChatDecoder()
        assertEquals(listOf(OpenAiDecodeEvent.TextDelta("A")), decoder.decode("""{"choices":[{"delta":{"content":"A"}}]}"""))
        assertEquals(listOf(OpenAiDecodeEvent.TextDelta("A")), decoder.decode("""{"choices":[{"delta":{"content":"A"}}]}"""))
        assertEquals(listOf(OpenAiDecodeEvent.TextDelta("AB")), decoder.decode("""{"choices":[{"delta":{"content":"AB"}}]}"""))
        assertEquals(
            listOf(OpenAiDecodeEvent.TextDelta("first"), OpenAiDecodeEvent.ReasoningDelta(" / second")),
            decoder.decode(
                """{"choices":[{"delta":{"content":[{"type":"text","text":"first"}]}}]}""",
            ) + decoder.decode(
                """{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[{"text":" / second"}]}]}}]}""",
            ),
        )
    }

    @Test
    fun `reasoning precedence follows desktop string rules`() {
        val decoder = OpenAiChatDecoder()
        assertEquals(
            listOf(
                OpenAiDecodeEvent.ReasoningDelta("kept"),
                OpenAiDecodeEvent.ReasoningDelta(""),
                OpenAiDecodeEvent.ReasoningDelta("fallback"),
                OpenAiDecodeEvent.ReasoningDelta("details"),
            ),
            decoder.decode("""{"choices":[{"delta":{"reasoning":"kept","reasoning_details":[{"text":"ignored"}]}}]}""") +
                decoder.decode("""{"choices":[{"delta":{"reasoning_content":"","reasoning":"ignored"}}]}""") +
                decoder.decode("""{"choices":[{"delta":{"reasoning_content":null,"reasoning":"fallback"}}]}""") +
                decoder.decode("""{"choices":[{"delta":{"reasoning_content":7,"reasoning_details":[{"text":"details"}]}}]}"""),
        )
    }

    @Test
    fun `tool deltas require a safe index and preserve meaningful raw arguments`() {
        val events = OpenAiChatDecoder().decode(
            """{"choices":[{"delta":{"tool_calls":[""" +
                """{"function":{"arguments":""}}, """ +
                """{"index":-1,"function":{"arguments":"x"}}, """ +
                """{"index":1,"function":{"arguments":"{"}}, """ +
                """{"index":2,"function":{"arguments":{"q":1}}}, """ +
                """{"index":3,"function":{"arguments":42}}, """ +
                """{"index":4,"id":"","function":{"name":"","arguments":""}}]}}]}""",
        )
        assertEquals(
            listOf(
                OpenAiDecodeEvent.ToolCallDelta(OpenAiToolCallDelta(1, argumentsFragment = "{")),
                OpenAiDecodeEvent.ToolCallDelta(OpenAiToolCallDelta(2, argumentsFragment = """{"q":1}""")),
                OpenAiDecodeEvent.ToolCallDelta(OpenAiToolCallDelta(3, argumentsFragment = "42")),
            ),
            events.map(::withoutRawFrame),
        )
    }

    @Test
    fun `usage omits unreported values instead of inventing zeros`() {
        val event = OpenAiChatDecoder().decode(
            """{"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":2}}""",
        ).single() as OpenAiDecodeEvent.UsageReported
        assertEquals(9L, event.usage.inputTokens)
        assertEquals(2L, event.usage.outputTokens)
        assertNull(event.usage.totalTokens)
        assertNull(event.usage.cachedInputTokens)
    }

    @Test
    fun `usage accepts nested prompt and input cache write aliases`() {
        for (details in listOf("prompt_tokens_details", "input_tokens_details")) {
            val event = OpenAiChatDecoder().decode(
                """{"usage":{"prompt_tokens":10,"completion_tokens":4,"$details":{"cache_write_tokens":3}}}""",
            ).single() as OpenAiDecodeEvent.UsageReported
            assertEquals(3L, event.usage.cacheWriteInputTokens)
        }
    }

    @Test
    fun `usage accepts input output and cache aliases`() {
        val event = OpenAiChatDecoder().decode(
            """{"usage":{"input_tokens":10,"output_tokens":4,"cache_read_input_tokens":3,""" +
                """"prompt_cache_hit_tokens":5,"cache_creation_input_tokens":2,""" +
                """"output_tokens_details":{"reasoning_tokens":1}}}""",
        ).single() as OpenAiDecodeEvent.UsageReported
        assertEquals(10L, event.usage.inputTokens)
        assertEquals(4L, event.usage.outputTokens)
        assertEquals(5L, event.usage.cachedInputTokens)
        assertEquals(2L, event.usage.cacheWriteInputTokens)
        assertEquals(1L, event.usage.reasoningTokens)
    }

    @Test
    fun `empty keepalive frame has no payload but usage only frame does`() {
        val decoder = OpenAiChatDecoder()
        assertEquals(emptyList<OpenAiDecodeEvent>(), decoder.decode(""))
        assertFalse(decoder.frameHasPayload())
        decoder.decode("""{"choices":[],"usage":{"prompt_tokens":1}}""")
        assertTrue(decoder.frameHasPayload())
    }

    private fun withoutRawFrame(event: OpenAiDecodeEvent): OpenAiDecodeEvent = when (event) {
        is OpenAiDecodeEvent.ToolCallDelta -> event.copy(call = event.call.copy(rawJson = null))
        else -> event
    }
}
