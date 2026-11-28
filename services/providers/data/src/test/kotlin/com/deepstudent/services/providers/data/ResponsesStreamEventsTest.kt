package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ResponsesMetadataKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event-shape semantics of the frozen desktop Responses stream: text fallbacks,
 * reasoning items, web-search payloads and the function-call assembler.
 */
class ResponsesStreamEventsTest {
    @Test
    fun `output text fallbacks do not duplicate`() {
        val doneOnly = responsesDecode(
            responsesFrame("""{"type":"response.output_text.done","text":"complete text"}"""),
            responsesFrame("""{"type":"response.completed","response":{"output_text":"complete text"}}"""),
        )
        assertEquals(listOf<ProviderStreamEvent>(ProviderStreamEvent.TextDelta("complete text")), doneOnly.first)

        val deltaFirst = responsesDecode(
            responsesFrame("""{"type":"response.output_text.delta","delta":"streamed"}"""),
            responsesFrame("""{"type":"response.output_text.done","text":"streamed"}"""),
            responsesFrame(
                """{"type":"response.completed","response":{"output":[{"type":"message","content":[""" +
                    """{"type":"output_text","text":"streamed"}]}]}}""",
            ),
        )
        assertEquals(listOf<ProviderStreamEvent>(ProviderStreamEvent.TextDelta("streamed")), deltaFirst.first)

        val convenience = responsesDecode(
            responsesFrame(
                """
                    {"type":"response.completed","response":{"output_text":"duplicate convenience value",
                    "output":[{"type":"message","content":[{"type":"output_text","text":"first"},
                    {"type":"output_text","text":" second"}]}]}}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf<ProviderStreamEvent>(ProviderStreamEvent.TextDelta("first second")), convenience.first)
    }

    @Test
    fun `reasoning summaries join with a blank line`() {
        val (events, _) = responsesDecode(
            responsesFrame(
                """{"type":"response.completed","response":{"output":[{"type":"reasoning","summary":[""" +
                    """{"type":"summary_text","text":"first"},{"type":"summary_text","text":"second"}]}]}}""",
            ),
        )
        assertEquals(ProviderStreamEvent.ReasoningDelta("first\n\nsecond"), events.first())
        val item = events.filterIsInstance<ProviderStreamEvent.ResponsesMetadata>().single()
        assertEquals(ResponsesMetadataKind.REASONING_ITEM, item.kind)
        assertEquals(0, item.index)
    }

    @Test
    fun `deepseek citations use unicode offsets and an https allowlist`() {
        val (events, _) = responsesDecode(
            responsesFrame(
                """{"type":"response.completed","response":{"status":"completed","output":[""" +
                    """{"type":"message","content":[{"type":"output_text","text":"🌱结论。","annotations":[""" +
                    """{"type":"url_citation","url":"https://example.com/a_(b)","start_index":0,"end_index":3},""" +
                    """{"type":"url_citation","url":"javascript:alert(1)","start_index":0,"end_index":4}]}]},""" +
                    """{"type":"function_call","id":"fc_1","call_id":"call_1","name":"lookup","arguments":"{}"}]}}""",
            ),
            moderated = true,
            webTools = true,
        )
        val text = events.filterIsInstance<ProviderStreamEvent.TextDelta>().map { it.text }
        assertEquals(listOf("🌱结论 [1](<https://example.com/a_(b)>)。"), text)
        assertEquals(1, events.filterIsInstance<ProviderStreamEvent.ToolCallDelta>().count { it.argumentsComplete })
    }

    @Test
    fun `web search progress and completed payloads deduplicate`() {
        val item = """
            {"type":"web_search_call","id":"ws_fixture","status":"completed","sources":[{"url":"https://example.com/1","title":"One"}]}
        """.trimIndent()
        val (events, _) = responsesDecode(
            responsesFrame("""{"type":"response.web_search_call.in_progress","item_id":"ws_fixture"}"""),
            responsesFrame("""{"type":"response.web_search_call.searching","item_id":"ws_fixture"}"""),
            responsesFrame("""{"type":"response.output_item.done","output_index":1,"item":$item}"""),
            responsesFrame("""{"type":"response.completed","response":{"output":[$item]}}"""),
        )
        val payloads = events.filterIsInstance<ProviderStreamEvent.ResponsesMetadata>()
        assertEquals(3, payloads.size)
        assertEquals("""{"id":"ws_fixture","stage":"in_progress"}""", payloads[0].rawJson)
        assertEquals("""{"id":"ws_fixture","stage":"searching"}""", payloads[1].rawJson)
        assertEquals(1, payloads[2].index)
        val completed = JsonValue.parseObject(payloads[2].rawJson)
        assertEquals("completed", completed.textAt("stage"))
        assertTrue(completed.containsKey("item"))
        // A wire-provided `sources` array replays verbatim, like the frozen adapter.
        assertEquals(
            listOf("""{"url":"https://example.com/1","title":"One"}"""),
            completed.arrayAt("sources")?.values?.map { (it as JsonValue.Object).literal() },
        )
    }

    @Test
    fun `tool arguments accumulate then the final snapshot replaces them`() {
        val added = """{"type":"function_call","id":"fc_fixture","call_id":"call_fixture","name":"fixture_lookup","arguments":""}"""
        val (events, result) = responsesDecode(
            responsesFrame("""{"type":"response.output_item.added","output_index":2,"item":$added}"""),
            responsesFrame(
                """{"type":"response.function_call_arguments.delta","item_id":"fc_fixture","output_index":2,"delta":"{\"q\""}""",
            ),
            responsesFrame(
                """{"type":"response.function_call_arguments.delta","item_id":"fc_fixture","output_index":2,"delta":":\"🧪\"}"}""",
            ),
            responsesFrame("""{"type":"response.output_item.done","output_index":2,"item":$added}"""),
            responsesFrame("""{"type":"response.completed","response":{"output":[$added]}}"""),
        )
        val tools = events.filterIsInstance<ProviderStreamEvent.ToolCallDelta>()
        assertEquals(4, tools.size)
        assertEquals("call_fixture", tools[0].id)
        assertEquals(false, tools[0].argumentsComplete)
        assertEquals("{\"q\"", tools[1].argumentsFragment)
        val snapshot = tools.last()
        assertTrue(snapshot.argumentsComplete)
        assertEquals("{\"q\":\"🧪\"}", snapshot.argumentsFragment)
        assertEquals(
            """{"index":2,"id":"call_fixture","type":"function",""" +
                """"function":{"name":"fixture_lookup","arguments":"{\"q\":\"🧪\"}"}}""",
            snapshot.rawJson,
        )
        assertEquals(ProviderStopReason.TOOL_CALL, checkNotNull(result).stopReason)
    }

    @Test
    fun `orphan argument deltas follow the frozen done frame lookup`() {
        // The frozen desktop keys the done-frame pending lookup on item_id only, so a
        // fragment addressed only by call_id stays withheld and the snapshot falls
        // back to the frame fields.
        val orphan = responsesDecode(
            responsesFrame("""{"type":"response.function_call_arguments.delta","call_id":"call_orphan","delta":"{\"a\""}"""),
            responsesFrame("""{"type":"response.function_call_arguments.done","call_id":"call_orphan","name":"lookup"}"""),
        )
        val orphanTools = orphan.first.filterIsInstance<ProviderStreamEvent.ToolCallDelta>()
        assertEquals(1, orphanTools.size)
        assertTrue(orphanTools.single().argumentsComplete)
        assertEquals("call_orphan", orphanTools.single().id)
        assertEquals("{}", orphanTools.single().argumentsFragment)
        assertEquals(0, orphanTools.single().index)

        // Fragments addressed by item id merge into the done snapshot.
        val merged = responsesDecode(
            responsesFrame("""{"type":"response.function_call_arguments.delta","item_id":"fc_x","output_index":4,"delta":"{\"a\""}"""),
            responsesFrame("""{"type":"response.function_call_arguments.done","item_id":"fc_x","name":"lookup"}"""),
        )
        val tools = merged.first.filterIsInstance<ProviderStreamEvent.ToolCallDelta>()
        assertEquals(1, tools.size)
        assertEquals("{\"a\"", tools.single().argumentsFragment)
        assertEquals("fc_x", tools.single().id)
        assertEquals(4, tools.single().index)
    }

    @Test
    fun `final reasoning items are deduplicated after streamed items`() {
        val identified = """{"type":"reasoning","id":"rs_fixture","summary":[{"type":"summary_text","text":"合成思考"}]}"""
        val unidentified = """{"type":"reasoning","summary":[{"type":"summary_text","text":"late"}]}"""
        val final = responsesFrame("""{"type":"response.completed","response":{"output":[$identified,$unidentified]}}""")
        val (events, _) = responsesDecode(
            responsesFrame("""{"type":"response.output_item.done","output_index":0,"item":$identified}"""),
            final,
        )
        val streamed = events.filterIsInstance<ProviderStreamEvent.ResponsesMetadata>().single()
        assertEquals(ResponsesMetadataKind.REASONING_ITEM, streamed.kind)
        assertEquals(identified, streamed.rawJson)
        assertEquals(0, streamed.index)

        val fallback = responsesDecode(final).first.filterIsInstance<ProviderStreamEvent.ResponsesMetadata>()
        assertEquals(listOf(identified, unidentified), fallback.map { it.rawJson })
    }
}
