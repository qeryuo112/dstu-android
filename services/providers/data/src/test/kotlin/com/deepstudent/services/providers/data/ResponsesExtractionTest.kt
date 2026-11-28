package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extraction helpers of the frozen desktop Responses final response
 * (`extract_output_text`, `extract_reasoning_text`, `deepseekRefusal`,
 * `deepseekCitedText`, `extract_web_search_sources`,
 * `full_web_search_item_for_replay`) and the wire accessors.
 */
class ResponsesExtractionTest {
    @Test
    fun `citation allowlist rejects schemes user info opaque hosts and bad offsets`() {
        val response = JsonValue.parseObject(
            """
            {"output":[{"type":"message","content":[{"type":"output_text","text":"正文一二三","annotations":[
            {"type":"url_citation","url":"ftp://example.com/x","start_index":0,"end_index":2},
            {"type":"url_citation","url":"https://user:pw@example.com/x","start_index":0,"end_index":2},
            {"type":"url_citation","url":"https:example.com/opaque","start_index":0,"end_index":2},
            {"type":"url_citation","url":"https://example.com/ok","start_index":2,"end_index":2},
            {"type":"url_citation","url":"https://example.com/ok","start_index":0,"end_index":99},
            {"type":"url_citation","url":"https://example.com/ok","start_index":1,"end_index":2},
            {"type":"url_citation","url":"<https://example.com/ok>","start_index":2,"end_index":3}]}]}]}
            """.trimIndent().replace("\n", ""),
        )
        // The marker is inserted after the code point each annotation ends on.
        assertEquals("正文 [1](<https://example.com/ok>)一二三", deepseekCitedText(response))
    }

    @Test
    fun `deepseek refusal falls back to the fixed phrase`() {
        val response = JsonValue.parseObject(
            """{"output":[{"type":"message","content":[{"type":"refusal","refusal":""}]}]}""",
        )
        assertEquals(RESPONSES_REFUSAL_FALLBACK, deepseekRefusal(response))
        assertNull(deepseekRefusal(JsonValue.Object.EMPTY))
    }

    @Test
    fun `usage keeps values beyond the signed 32-bit range and clamps to the unsigned range`() {
        val usage = responsesUsage(
            JsonValue.parseObject("""{"input_tokens":2147483650,"output_tokens":5,"total_tokens":2147483655}"""),
        )
        assertEquals(2_147_483_650L, usage?.inputTokens)
        assertEquals(2_147_483_655L, usage?.totalTokens)

        val clamped = responsesUsage(JsonValue.parseObject("""{"prompt_tokens":999999999999}"""))
        assertEquals(0xFFFF_FFFFL, clamped?.inputTokens)

        val inconsistent = responsesUsage(
            JsonValue.parseObject("""{"input_tokens":2,"output_tokens":3,"total_tokens":7}"""),
        )
        assertEquals(2L, inconsistent?.inputTokens)
        assertEquals(3L, inconsistent?.outputTokens)
        assertNull("an inconsistent wire total is dropped, not a failed call", inconsistent?.totalTokens)

        val derived = responsesUsage(JsonValue.parseObject("""{"input_tokens":0,"output_tokens":3,"total_tokens":7}"""))
        assertEquals(4L, derived?.inputTokens)
        assertEquals(7L, derived?.totalTokens)

        val junk = responsesUsage(JsonValue.parseObject("""{"input_tokens":4.5,"output_tokens":-3}"""))
        assertNull(junk)
        assertNull(responsesUsage(null))
    }

    @Test
    fun `web search sources normalize every frozen shape`() {
        val searchResults = extractWebSearchSources(
            JsonValue.parseObject("""{"search_results":[{"url":"https://example.com/a","title":"A","text":"alpha"}]}"""),
        )
        assertEquals(
            listOf("""{"title":"A","url":"https://example.com/a","snippet":"alpha"}"""),
            searchResults.map { it.literal() },
        )

        val alias = extractWebSearchSources(
            JsonValue.parseObject("""{"web_search_sources":[{"url":"https://example.com/b","title":"B"}]}"""),
        )
        assertEquals("https://example.com/b", alias.single().textAt("url"))

        val action = extractWebSearchSources(
            JsonValue.parseObject("""{"action":{"sources":[{"url":"https://example.com/c"}]}}"""),
        )
        assertEquals(listOf("https://example.com/c"), action.map { it.textAt("url") })

        val page = extractWebSearchSources(
            JsonValue.parseObject("""{"page":{"url":"https://example.com/d","title":"D"}}"""),
        )
        assertEquals(
            listOf("""{"title":"D","url":"https://example.com/d","snippet":""}"""),
            page.map { it.literal() },
        )

        val annotations = extractWebSearchSources(
            JsonValue.parseObject(
                """
                {"content":[{"type":"output_text","text":"x","annotations":[
                {"type":"url_citation","url":"https://example.com/e","title":"E","snippet":"s"},
                {"type":"url_citation","url":"https://example.com/e","title":"again"},
                {"type":"url_citation","url":""}]}]}
                """.trimIndent().replace("\n", ""),
            ),
        )
        assertEquals(1, annotations.size)
        assertEquals("E", annotations.single().textAt("title"))
    }

    @Test
    fun `full web search item replays typed items and promotes untyped records`() {
        val typed = JsonValue.parseObject("""{"type":"web_search_call","id":"ws","status":"completed","signature":"s"}""")
        assertEquals(typed, fullWebSearchItem(typed))
        val untyped = JsonValue.parseObject("""{"id":"ws","status":"in_progress"}""")
        assertEquals(
            JsonValue.parseObject("""{"id":"ws","status":"in_progress","type":"web_search_call"}"""),
            fullWebSearchItem(untyped),
        )
        val nonStringType = JsonValue.parseObject("""{"id":"ws","status":"done","type":5}""")
        assertEquals(
            JsonValue.parseObject("""{"id":"ws","status":"done","type":"web_search_call"}"""),
            fullWebSearchItem(nonStringType),
        )
        assertNull(fullWebSearchItem(JsonValue.parseObject("""{"id":"ws"}""")))
        assertNull(fullWebSearchItem(JsonValue.parseObject("""{"type":"message","id":"m"}""")))
    }

    @Test
    fun `wire accessors mirror the desktop json helpers`() {
        val frame = JsonValue.parseObject(
            """{"text":"s","small":42,"number":2147483650,"clamped":999999999999,"negative":-3,""" +
                """"huge":99999999999999999999,"float":4.5,"object":{"a":1},"array":[1],"null":null}""",
        )
        assertEquals("s", frame.textAt("text"))
        assertNull(frame.textAt("missing"))
        assertNull(frame.textAt("number"))
        assertNull(frame.textAt("null"))
        assertNull(frame.objectAt("array"))
        assertEquals(1L, frame.objectAt("object")?.i64At("a"))
        assertNull(frame.arrayAt("object"))
        assertEquals(2147483650L, frame.i64At("number"))
        assertEquals(-3L, frame.i64At("negative"))
        assertNull(frame.i64At("huge"))
        assertNull(frame.i64At("float"))
        assertEquals(42, frame.indexAt("small"))
        assertNull("the stream index boundary is a non-negative 32-bit value", frame.indexAt("number"))
        assertNull(frame.indexAt("negative"))
        assertNull(frame.indexAt("huge"))
        assertNull(frame.u32At("negative"))
        assertNull(frame.u32At("huge"))
        assertEquals(0xFFFF_FFFFL, frame.u32At("clamped"))
        assertTrue(frame.keys().contains("small"))
    }
}
