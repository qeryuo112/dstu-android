package com.deepstudent.services.providers.data

import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminal and moderation semantics of the frozen desktop Responses stream decoder,
 * mirroring `host/plugins/llm-providers/tests/openai-responses.test.ts` cases that the
 * five application replay sequences do not cover. The end-to-end oracle sequences stay
 * in the app module's `S305FrozenResponsesTest`.
 */
class OpenAiResponsesDecoderTest {
    @Test
    fun `unmoderated done marker closes the stream as a completion`() {
        val (events, result) = responsesDecode(
            responsesFrame("""{"type":"response.output_text.delta","delta":"partial"}"""),
            responsesFrame("[DONE]"),
        )
        assertEquals(listOf<ProviderStreamEvent>(ProviderStreamEvent.TextDelta("partial")), events)
        assertEquals("partial", checkNotNull(result).text)
        assertEquals(ProviderStopReason.STOP, result.stopReason)
    }

    @Test
    fun `clean eof without a terminal event is a truncation`() {
        val code = responsesFailureCode(responsesFrame("""{"type":"response.output_text.delta","delta":"partial"}"""))
        assertEquals(ProviderFailureCode.STREAM_TRUNCATED, code)
    }

    @Test
    fun `moderated done marker is a truncation without releasing text`() {
        val (events, result) = responsesDecode(
            responsesFrame("""{"type":"response.output_text.delta","delta":"unsafe"}"""),
            responsesFrame("[DONE]"),
            moderated = true,
            webTools = true,
        )
        assertTrue(events.isEmpty())
        assertNull(result)
        assertEquals(
            ProviderFailureCode.STREAM_TRUNCATED,
            responsesFailureCode(responsesFrame("[DONE]"), moderated = true),
        )
    }

    @Test
    fun `untyped stream error is a fixed provider error`() {
        val frame = responsesFrame("""{"error":{"code":402,"message":"Insufficient credits"}}""")
        val (events, _) = responsesDecode(frame)
        assertTrue(events.isEmpty())
        assertEquals(ProviderFailureCode.PROVIDER_ERROR, responsesFailureCode(frame))
    }

    @Test
    fun `response failed reports usage once then fails`() {
        val frame = responsesFrame(
            """
                {"type":"response.failed","response":{"status":"failed",
                "usage":{"input_tokens":2147483650,"output_tokens":5,"total_tokens":2147483655}}}
            """.trimIndent(),
        )
        val (events, _) = responsesDecode(frame)
        assertEquals(1, events.size)
        val usage = (events.single() as ProviderStreamEvent.UsageReported).usage
        assertEquals(2_147_483_650L, usage.inputTokens)
        assertEquals(5L, usage.outputTokens)
        assertEquals(
            ProviderFailureCode.PROVIDER_ERROR,
            responsesFailureCode(responsesFrame("""{"type":"response.failed"}""")),
        )
    }

    @Test
    fun `moderated truncation releases no unreviewed text or tools`() {
        val incomplete = responsesFrame(moderatedIncomplete())
        val (events, _) = responsesDecode(
            responsesFrame("""{"type":"response.output_text.delta","delta":"unsafe"}"""),
            incomplete,
            moderated = true,
            webTools = true,
        )
        assertTrue("Android must not release unreviewed DeepSeek output on truncation", events.isEmpty())
        assertEquals(ProviderFailureCode.STREAM_TRUNCATED, responsesFailureCode(incomplete, moderated = true))
    }

    @Test
    fun `unmoderated incomplete keeps partial text and usage without completing`() {
        val incomplete = responsesFrame(
            """
            {"type":"response.incomplete","response":{"status":"incomplete",
            "incomplete_details":{"reason":"max_output_tokens"},
            "output":[{"type":"message","content":[{"type":"output_text","text":"partial answer"}]}],
            "usage":{"output_tokens":128}}}
            """.trimIndent().replace("\n", ""),
        )
        val (events, _) = responsesDecode(incomplete)
        assertEquals(2, events.size)
        assertEquals(ProviderStreamEvent.TextDelta("partial answer"), events[0])
        assertEquals(128L, (events[1] as ProviderStreamEvent.UsageReported).usage.outputTokens)
        assertEquals(ProviderFailureCode.STREAM_TRUNCATED, responsesFailureCode(incomplete))
    }

    @Test
    fun `moderation block reports usage then a fixed provider error`() {
        val blocked = responsesFrame(
            """
            {"type":"response.completed","response":{"status":"completed","usage":{"output_tokens":7},
            "output":[{"type":"message","content":[{"type":"refusal","refusal":""}]}]}}
            """.trimIndent().replace("\n", ""),
        )
        val (events, _) = responsesDecode(blocked, moderated = true)
        assertEquals(1, events.size)
        assertEquals(7L, (events.single() as ProviderStreamEvent.UsageReported).usage.outputTokens)
        assertEquals(ProviderFailureCode.PROVIDER_ERROR, responsesFailureCode(blocked, moderated = true))
    }

    @Test
    fun `decoded metadata keeps the private payload out of diagnostics`() {
        val (events, _) = responsesDecode(
            responsesFrame("""{"type":"response.output_text.delta","delta":"哈"}"""),
            responsesFrame(
                """{"type":"response.completed","response":{"output":[{"type":"reasoning","id":"rs",""" +
                    """"summary":[{"type":"summary_text","text":"合成思考"}],"encrypted_content":"secret"}]}}""",
            ),
        )
        val metadata = events.filterIsInstance<ProviderStreamEvent.ResponsesMetadata>().single()
        assertTrue(metadata.toString().startsWith("ProviderStreamEvent.ResponsesMetadata("))
        assertFalse(metadata.toString().contains("secret"))
    }

    private fun moderatedIncomplete() = """
        {"type":"response.incomplete","response":{"status":"incomplete",
        "output":[{"type":"message","content":[{"type":"output_text","text":"partial"}]},
        {"type":"function_call","id":"fc_1","call_id":"call_1","name":"lookup","arguments":"{}"}],
        "incomplete_details":{"reason":"max_output_tokens"}}}
    """.trimIndent().replace("\n", "")
}
