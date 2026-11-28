package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frozen stream expectations (`llm-providers/tests/anthropic.test.ts`) plus the Android safety boundary. */
class AnthropicStreamDecoderTest {
    @Test
    fun signatureFragmentsAccumulateIntoTheReplayBlock(): Unit = runBlocking {
        val decoder = AnthropicStreamDecoder()
        val events = decoder.decode(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"推理"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"EqQBCg"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"XYZ123"}}""",
            """{"type":"content_block_stop","index":0}""",
        )
        assertEquals(listOf<ProviderStreamEvent>(ProviderStreamEvent.ReasoningDelta("推理")), events)
        assertEquals(
            listOf<JsonValue>(
                JsonValue.obj(
                    "type" to JsonValue.text("thinking"),
                    "thinking" to JsonValue.text("推理"),
                    "signature" to JsonValue.text("EqQBCgXYZ123"),
                ),
            ),
            decoder.replayThinkingBlocks(),
        )
    }

    @Test
    fun framedSseBlocksAndBareNdjsonBothDecode(): Unit = runBlocking {
        val framed = AnthropicStreamDecoder().decode(
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"framed\"}}",
        )
        assertEquals(listOf<ProviderStreamEvent>(ProviderStreamEvent.TextDelta("framed")), framed)
        val bare = AnthropicStreamDecoder().decode(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ndjson"}}""",
        )
        assertEquals(listOf<ProviderStreamEvent>(ProviderStreamEvent.TextDelta("ndjson")), bare)
    }

    @Test
    fun usageMergesCacheFieldsFromMessageStart(): Unit = runBlocking {
        val decoder = AnthropicStreamDecoder()
        val start = decoder.decode(
            """{"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":10,"cache_read_input_tokens":900,"cache_creation_input_tokens":50,"output_tokens":0}}}""",
        )
        assertEquals(900L, start.filterIsInstance<ProviderStreamEvent.UsageReported>().single().usage.cachedInputTokens)
        val delta = decoder.decode("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}""")
        val merged = delta.filterIsInstance<ProviderStreamEvent.UsageReported>().single().usage
        assertEquals(10L, merged.inputTokens)
        assertEquals(42L, merged.outputTokens)
        assertEquals(900L, merged.cachedInputTokens)
        assertEquals(50L, merged.cacheWriteInputTokens)
        assertTrue(decoder.decode("""{"type":"message_stop"}""").isEmpty())
        val finished = decoder.finish(SseEnd.COMPLETE)
        assertEquals(ProviderStopReason.STOP, finished.stopReason)
        assertEquals(merged, finished.usage)

        val next = AnthropicStreamDecoder()
        next.decode("""{"type":"message_start","message":{"id":"msg_2","usage":{"input_tokens":3,"output_tokens":0}}}""")
        val afterStop = next.decode(
            """{"type":"message_stop"}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""",
        )
        val reset = afterStop.filterIsInstance<ProviderStreamEvent.UsageReported>().single().usage
        assertEquals(7L, reset.outputTokens)
        assertNull(reset.cachedInputTokens)
    }

    @Test
    fun terminalNonZeroFieldsWinTheMerge(): Unit = runBlocking {
        val decoder = AnthropicStreamDecoder()
        decoder.decode("""{"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":10,"cache_read_input_tokens":900}}}""")
        val delta = decoder.decode(
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42,"cache_read_input_tokens":1200}}""",
        )
        val usage = delta.filterIsInstance<ProviderStreamEvent.UsageReported>().single().usage
        assertEquals(1_200L, usage.cachedInputTokens)
        assertEquals(10L, usage.inputTokens)
    }

    @Test
    fun observedZeroCacheValuesStayVisible(): Unit = runBlocking {
        val usage = AnthropicUsage.build(
            JsonValue.parseObject(
                """{"input_tokens":10,"output_tokens":5,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0}}""",
            ),
        )
        assertEquals(0L, usage.cachedInputTokens)
        assertEquals(0L, usage.cacheWriteInputTokens)
        val unmeasured = AnthropicUsage.build(JsonValue.parseObject("""{"input_tokens":10,"output_tokens":5}"""))
        assertNull(unmeasured.cachedInputTokens)
        assertNull(unmeasured.cacheWriteInputTokens)
    }

    @Test
    fun usagePreservesWideCountsWithoutInventingMissingCounters() {
        val usage = AnthropicUsage.build(JsonValue.parseObject("""{"input_tokens":4000000000,"output_tokens":7,"total_tokens":5}"""))
        assertEquals(4_000_000_000L, usage.inputTokens)
        assertEquals(7L, usage.outputTokens)
        assertNull(usage.totalTokens)
        val partial = AnthropicUsage.build(JsonValue.parseObject("""{"output_tokens":0}"""))
        assertNull(partial.inputTokens)
        assertNull(partial.totalTokens)
        assertEquals(0L, partial.outputTokens)
    }

    @Test
    fun errorEventFailsWithoutForwardingDetail(): Unit = runBlocking {
        val decoder = AnthropicStreamDecoder()
        val events = decoder.decode("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        assertTrue(events.isEmpty())
        assertEquals(ProviderFailureCode.PROVIDER_ERROR, decoder.failureCode())
    }

    @Test
    fun refusalAndContextStopsNeverComplete(): Unit = runBlocking {
        val refusal = AnthropicStreamDecoder()
        val refusalEvents = refusal.decode(
            """{"type":"message_delta","delta":{"stop_reason":"refusal","stop_details":{"category":"cyber"}}}""",
        )
        assertTrue(refusalEvents.isEmpty())
        assertEquals(ProviderFailureCode.PROVIDER_ERROR, refusal.failureCode())

        val context = AnthropicStreamDecoder()
        val contextEvents = context.decode(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"部分"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"model_context_window_exceeded"}}""",
        )
        assertEquals(listOf<ProviderStreamEvent>(ProviderStreamEvent.TextDelta("部分")), contextEvents)
        assertEquals(ProviderFailureCode.STREAM_TRUNCATED, context.failureCode())
    }

    @Test
    fun truncatedToolArgumentsNeverArmACall(): Unit = runBlocking {
        val decoder = AnthropicStreamDecoder()
        val events = decoder.decode(
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_2","name":"lookup_notes","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"q\":\"北"}}""",
            """{"type":"content_block_stop","index":1}""",
        )
        assertTrue(events.isEmpty())
        assertEquals(ProviderFailureCode.PROVIDER_ERROR, decoder.failureCode())
    }

    @Test
    fun aTerminalMarkerCannotApproveAnUnclosedToolBlock(): Unit = runBlocking {
        for (terminal in listOf("[DONE]", """{"type":"message_stop"}""")) {
            val decoder = AnthropicStreamDecoder()
            val events = decoder.decode(
                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t","name":"lookup","input":{}}}""",
                terminal,
            )
            assertTrue(events.isEmpty())
            assertEquals(ProviderFailureCode.STREAM_TRUNCATED, decoder.failureCode())
        }
    }

    @Test
    fun missingToolIdentityNeverArmsACall(): Unit = runBlocking {
        val decoder = AnthropicStreamDecoder()
        val events = decoder.decode(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","name":"lookup_notes","input":{"q":"x"}}}""",
            """{"type":"content_block_stop","index":0}""",
        )
        assertTrue(events.isEmpty())
        assertEquals(ProviderFailureCode.PROVIDER_ERROR, decoder.failureCode())
    }

    @Test
    fun blankArgumentsFallBackToTheStartBlockInput(): Unit = runBlocking {
        val decoder = AnthropicStreamDecoder()
        val events = decoder.decode(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"lookup","input":{"q":"preset"}}}""",
            """{"type":"content_block_stop","index":0}""",
        )
        val tool = events.filterIsInstance<ProviderStreamEvent.ToolCallDelta>().single()
        assertEquals(
            "{\"q\":\"preset\"}",
            JsonValue.parseObject(checkNotNull(tool.rawJson))["function"]?.let {
                ResponsesJson.field(it, "arguments")
            }?.let { ResponsesJson.text(it) },
        )
    }

    @Test
    fun jsonNullArgumentsAreValidToolArguments(): Unit = runBlocking {
        val decoder = AnthropicStreamDecoder()
        val events = decoder.decode(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"lookup"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"null"}}""",
            """{"type":"content_block_stop","index":0}""",
        )
        val tool = events.filterIsInstance<ProviderStreamEvent.ToolCallDelta>().single()
        assertTrue(tool.argumentsComplete)
        assertEquals("null", argumentsOf(rawFunctionOf(tool)))
    }

    @Test
    fun emptyTextDeltasAreSkippedButStillTerminatedByTheDoneMarker(): Unit = runBlocking {
        val decoder = AnthropicStreamDecoder()
        val events = decoder.decode(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":""}}""",
            "[DONE]",
        )
        assertTrue(events.isEmpty())
        val result = decoder.finish(SseEnd.COMPLETE)
        assertEquals(ProviderStopReason.STOP, result.stopReason)
        assertEquals("", result.text)
    }

    @Test
    fun cleanEofWithoutAnExplicitTerminalIsATruncation(): Unit = runBlocking {
        val decoder = AnthropicStreamDecoder()
        decoder.decode("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}""")
        val failure = runCatching { decoder.finish(SseEnd.COMPLETE) }
        assertEquals(ProviderFailureCode.STREAM_TRUNCATED, failure.exceptionOrNull()?.let { (it as ProviderException).failure.code })
        assertEquals(ProviderFailureCode.STREAM_TRUNCATED, AnthropicStreamDecoder().failureOr { it.finish(SseEnd.TRUNCATED) })
    }

    @Test
    fun maxTokensStopReasonMapsToLength(): Unit = runBlocking {
        val decoder = AnthropicStreamDecoder()
        decoder.decode(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"cut"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""",
            """{"type":"message_stop"}""",
        )
        val result = decoder.finish(SseEnd.COMPLETE)
        assertEquals(ProviderStopReason.LENGTH, result.stopReason)
        assertEquals("cut", result.text)
    }

    private suspend fun AnthropicStreamDecoder.decode(vararg frames: String): List<ProviderStreamEvent> {
        val collected = mutableListOf<ProviderStreamEvent>()
        for (frame in frames) acceptBlock(frame) { collected += it }
        return collected
    }

    private fun AnthropicStreamDecoder.failureCode(): ProviderFailureCode {
        val failure = runCatching { finish(SseEnd.COMPLETE) }.exceptionOrNull() as? ProviderException
        return failure?.failure?.code ?: error("expected a fixed failure")
    }

    private fun AnthropicStreamDecoder.failureOr(block: (AnthropicStreamDecoder) -> Unit): ProviderFailureCode = try {
        block(this)
        error("expected a fixed failure")
    } catch (failure: ProviderException) {
        failure.failure.code
    }

    private fun rawFunctionOf(tool: ProviderStreamEvent.ToolCallDelta): JsonValue =
        JsonValue.parseObject(checkNotNull(tool.rawJson))["function"]!!

    private fun argumentsOf(function: JsonValue): String? = ResponsesJson.text(ResponsesJson.field(function, "arguments"))
}
