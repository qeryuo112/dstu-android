package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.network.NetworkPhase
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderResponsesHistory
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ResponsesMetadataKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(Parameterized::class)
class S305FrozenRequestTest(private val id: String) {
    @Test
    fun postsExactFrozenRequestThroughActualApplicationRoute() = runBlocking {
        val case = S305ReplayFixture.case(id)
        ProviderReplayPeer(s305Ack()).use { peer ->
            S305ReplaySession(case, listOf(peer)).use { session ->
                // An alternate configured host does not receive a Codex account token.
                val selection = if (case.codex) case.selection("https://not-codex.invalid/v8") else case.selection()
                assertEquals("ack", session.call(selection = selection).s305Success().text)
                val captured = peer.captured()
                session.assertWireRequest(captured)
                session.assertRedacted()
                assertEquals(1, session.requests)
                s305Capture(
                    case,
                    "request",
                    JsonValue.Object.Builder().put("path", captured.path).put("method", captured.method)
                        .put("auth", case.auth).put("body", JsonValue.parseObject(captured.body)).build(),
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<String>> = S305ReplayFixture.cases.map { arrayOf(it.id) }
    }
}

@RunWith(Parameterized::class)
class S305FrozenStreamTest(private val id: String) {
    @Test
    fun deliversFrozenEventsInOrderAndClosesOnTerminal() = runBlocking {
        val case = S305ReplayFixture.case(id)
        ProviderReplayPeer(case.reply()).use { peer ->
            S305ReplaySession(case, listOf(peer)).use { session ->
                val events = mutableListOf<ProviderStreamEvent>()
                val result = session.call { events += it }.s305Success()
                for (metadata in events.filterIsInstance<ProviderStreamEvent.ResponsesMetadata>()) {
                    assertEquals(case.expectedHistoryScope, metadata.stateScope)
                }
                val actual = events.map(::s305Event)
                val expected = checkNotNull(case.stream).array("events").map { it as JsonValue.Object }
                assertEquals(expected, actual)
                val text = expected.filter { it.text("kind") == "text" }.joinToString("") { it.text("value") }
                assertEquals(text, result.text)
                val expectedStop = if (expected.any { it.text("kind") == "tool" }) ProviderStopReason.TOOL_CALL else ProviderStopReason.STOP
                assertEquals(expectedStop, result.stopReason)
                assertEquals(2_147_483_650L, result.usage?.inputTokens)
                assertEquals(result.usage, (events.last() as ProviderStreamEvent.Completed).usage)
                assertEquals(expectedStop, (events.last() as ProviderStreamEvent.Completed).stopReason)
                assertTrue("connection stayed open after Responses terminal event", peer.disconnected())
                assertEquals(listOf(NetworkPhase.STARTED, NetworkPhase.COMPLETED), session.phases.toList())
                session.assertWireRequest(peer.captured())
                session.assertRedacted()
                val receipt = JsonValue.Object.Builder().put("events", JsonValue.array(actual)).put("peerClosed", true).build()
                s305Capture(case, "stream", receipt)
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<String>> = S305ReplayFixture.primary.map { arrayOf(it.id) }
    }
}

@RunWith(Parameterized::class)
class S305ErrorCancelTest(private val id: String) {
    private val case = S305ReplayFixture.case(id)

    @Test
    fun reportsRedactedRejectionsAndCancelsTheOpenSocket() = runBlocking {
        verifyRejections()
        verifyProtocolError()
        verifyCancellation()
        s305Capture(
            case,
            "error-cancel",
            JsonValue.Object.Builder().put("http400", true).put("http401", true).put("http403", true).put("http429", true)
                .put("inlineError", true).put("preCancelled", true).put("flagCancelsOpenPeer", true).put("noFalseCompletion", true).build(),
        )
    }

    private suspend fun verifyRejections() {
        val statuses = mapOf(
            400 to ProviderFailureCode.PROVIDER_ERROR,
            401 to ProviderFailureCode.AUTHENTICATION,
            403 to ProviderFailureCode.AUTHENTICATION,
            429 to ProviderFailureCode.RATE_LIMITED,
        )
        for ((status, expected) in statuses) {
            ProviderReplayPeer(ProviderReplayReply.rejection(status)).use { peer ->
                val auth = S305ReplayAuth().apply {
                    if (status == 401) refreshResult = ProviderResult.Failure(ProviderFailure(expected))
                }
                S305ReplaySession(case, listOf(peer), auth).use { session ->
                    assertS305Failure(expected, session.call { error("rejections emit no events") })
                    assertEquals(1, session.requests)
                    assertEquals(if (case.codex && status == 401) listOf(42L) else emptyList<Long>(), auth.refreshed)
                    session.assertRedacted()
                }
            }
        }
    }

    private suspend fun verifyProtocolError() {
        val frame = """{"type":"error","error":{"message":"$REPLAY_PRIVATE_DETAIL"}}"""
        ProviderReplayPeer(ProviderReplayReply.sse(listOf(frame), keepOpen = true)).use { peer ->
            S305ReplaySession(case, listOf(peer)).use { session ->
                assertS305Failure(ProviderFailureCode.PROVIDER_ERROR, session.call { error("errors emit no data") })
                assertTrue("error frame didn't close peer", peer.disconnected())
                session.assertRedacted()
            }
        }
    }

    private suspend fun verifyCancellation() {
        S305ReplaySession(case, emptyList()).use { session ->
            assertEquals(ProviderResult.Cancelled, session.call(cancellation = ProviderCancellation { true }))
            assertEquals(0, session.requests)
            assertEquals(0, session.auth.requests)
        }
        val initial = """{"type":"response.output_text.delta","delta":"partial"}"""
        ProviderReplayPeer(ProviderReplayReply.sse(listOf(initial), keepOpen = true)).use { peer ->
            S305ReplaySession(case, listOf(peer)).use { session ->
                val stop = AtomicBoolean()
                val events = mutableListOf<ProviderStreamEvent>()
                val result = kotlinx.coroutines.coroutineScope {
                    val received = CompletableDeferred<Unit>()
                    val call = async {
                        session.call(cancellation = ProviderCancellation(stop::get)) {
                            events += it
                            received.complete(Unit)
                        }
                    }
                    peer.captured()
                    if (id != "deepseek") withTimeout(2_000) { received.await() }
                    stop.set(true)
                    call.await()
                }
                assertEquals(ProviderResult.Cancelled, result)
                assertTrue("token didn't close peer after it stopped sending", peer.disconnected())
                assertFalse(events.any { it is ProviderStreamEvent.Completed })
                assertEquals(listOf(NetworkPhase.STARTED, NetworkPhase.CANCELLED), session.phases.toList())
                session.assertRedacted()
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<String>> = S305ReplayFixture.primary.map { arrayOf(it.id) }
    }
}

class S305ResponsesBoundaryTest {
    @Test
    fun textOnlyRequestDoesNotSendAToolChoice() = runBlocking {
        val case = S305ReplayFixture.case("openai")
        ProviderReplayPeer(s305Ack()).use { peer ->
            S305ReplaySession(case, listOf(peer)).use { session ->
                val messages = listOf(ProviderMessage(ProviderRole.USER, "plain text"))
                assertEquals("ack", session.call(messages = messages, tools = emptyList()).s305Success().text)
                val body = JsonValue.parseObject(peer.captured().body)
                assertFalse(body.containsKey("tools"))
                assertFalse(body.containsKey("tool_choice"))
            }
        }
    }

    @Test
    fun toolSnapshotAndEncryptedReasoningCanBeSentBackInTheNextRequest() = runBlocking {
        val case = S305ReplayFixture.case("openai")
        val events = mutableListOf<ProviderStreamEvent>()
        ProviderReplayPeer(case.reply()).use { peer ->
            S305ReplaySession(case, listOf(peer)).use { session -> session.call { events += it }.s305Success() }
        }
        val reasoning = events.reasoningItem()
        val tool = events.filterIsInstance<ProviderStreamEvent.ToolCallDelta>().single { it.argumentsComplete }
        val historyCall = JsonValue.parseObject(checkNotNull(tool.rawJson))
        val withoutIndex = JsonValue.obj(
            "id" to checkNotNull(historyCall["id"]),
            "type" to checkNotNull(historyCall["type"]),
            "function" to checkNotNull(historyCall["function"]),
        )
        val history = listOf(
            ProviderMessage(
                ProviderRole.ASSISTANT,
                "previous",
                toolCallsJson = JsonValue.array(listOf(withoutIndex)).literal(),
                responses = ProviderResponsesHistory(
                    reasoningItemJson = reasoning.rawJson,
                    stateScope = checkNotNull(reasoning.stateScope),
                ),
            ),
            ProviderMessage(ProviderRole.TOOL, "tool result", toolCallId = tool.id),
            ProviderMessage(ProviderRole.USER, "continue"),
        )
        ProviderReplayPeer(s305Ack()).use { peer ->
            S305ReplaySession(case, listOf(peer)).use { session ->
                session.call(messages = history).s305Success()
                val items = JsonValue.parseObject(peer.captured().body).array("input").map { it as JsonValue.Object }
                assertEquals(JsonValue.parse(reasoning.rawJson), items[0])
                assertEquals("assistant", items[1].text("role"))
                assertEquals("function_call", items[2].text("type"))
                assertEquals(tool.argumentsFragment, items[2].text("arguments"))
                assertEquals("function_call_output", items[3].text("type"))
                assertEquals(items[2].text("call_id"), items[3].text("call_id"))
                assertEquals("user", items[4].text("role"))
            }
        }
    }

    @Test
    fun changingTheModelDoesNotForwardScopedPrivateHistory() = runBlocking {
        val case = S305ReplayFixture.case("openai")
        val events = mutableListOf<ProviderStreamEvent>()
        ProviderReplayPeer(case.reply()).use { peer ->
            S305ReplaySession(case, listOf(peer)).use { session -> session.call { events += it }.s305Success() }
        }
        val prior = events.reasoningItem()
        val history = listOf(
            ProviderMessage(
                ProviderRole.ASSISTANT,
                "visible answer",
                responses = ProviderResponsesHistory(
                    reasoningItemJson = prior.rawJson,
                    webSearchItemsJson = """[{"type":"web_search_call","id":"ws_private","signature":"fixture-only-search-signature"}]""",
                    stateScope = checkNotNull(prior.stateScope),
                ),
            ),
            ProviderMessage(ProviderRole.USER, "continue"),
        )
        val selected = case.selection().let { it.copy(profile = it.profile.copy(model = "gpt-5.6-another-fixture")) }
        ProviderReplayPeer(s305Ack()).use { peer ->
            S305ReplaySession(case, listOf(peer)).use { session ->
                session.call(selection = selected, messages = history).s305Success()
                val body = JsonValue.parseObject(peer.captured().body)
                assertEquals(listOf("assistant", "user"), body.array("input").map { (it as JsonValue.Object).text("role") })
                assertTrue(body.literal().contains("visible answer"))
                assertFalse(body.literal().contains("encrypted_content\":\"fixture-only-encrypted-reasoning"))
                assertFalse(body.literal().contains("fixture-only-search-signature"))
            }
        }
    }

    @Test
    fun doneMarkerDoesNotApproveModeratedOutput() = runBlocking {
        val partial = """{"type":"response.output_text.delta","delta":"partial"}"""
        for (id in listOf("openai", "deepseek")) {
            val case = S305ReplayFixture.case(id)
            ProviderReplayPeer(ProviderReplayReply.sse(listOf(partial, "[DONE]"), keepOpen = true)).use { peer ->
                S305ReplaySession(case, listOf(peer)).use { session ->
                    val events = mutableListOf<ProviderStreamEvent>()
                    val result = session.call { events += it }
                    if (id == "deepseek") {
                        assertS305Failure(ProviderFailureCode.STREAM_TRUNCATED, result)
                        assertTrue(events.isEmpty())
                    } else {
                        assertEquals("partial", result.s305Success().text)
                        assertEquals(ProviderStreamEvent.TextDelta("partial"), events.first())
                        assertTrue(events.last() is ProviderStreamEvent.Completed)
                    }
                    assertTrue(peer.disconnected())
                }
            }
        }
    }

    @Test
    fun cleanEofAfterPartialOutputIsNotACompletion() = runBlocking {
        val events = collectBoundary("openai", """{"type":"response.output_text.delta","delta":"partial"}""")
        assertEquals(listOf(ProviderStreamEvent.TextDelta("partial")), events)
    }

    @Test
    fun incompleteOutputKeepsOpenAiPartialTextButNotDeepSeekUnreviewedTextOrTools() = runBlocking {
        val incomplete = """
            {"type":"response.incomplete","response":{"status":"incomplete",
            "output":[{"type":"message","content":[{"type":"output_text","text":"partial"}]}],"incomplete_details":{"reason":"max_output_tokens"}}}
        """.trimIndent().replace("\n", "")
        val events = collectBoundary("openai", incomplete)
        assertEquals(listOf(ProviderStreamEvent.TextDelta("partial")), events)
        val deepSeek = collectBoundary("deepseek", """{"type":"response.output_text.delta","delta":"unsafe"}""", incomplete)
        assertEquals(emptyList<ProviderStreamEvent>(), deepSeek)
    }

    @Test
    fun coroutineCancellationAlsoClosesConnectionWithoutCompletion() = runBlocking {
        val case = S305ReplayFixture.case("xai")
        ProviderReplayPeer(ProviderReplayReply.sse(listOf("""{"type":"response.created"}"""), keepOpen = true)).use { peer ->
            S305ReplaySession(case, listOf(peer)).use { session ->
                val call = async { session.call { error("unexpected event") } }
                peer.captured()
                call.cancelAndJoin()
                assertTrue(peer.disconnected())
            }
        }
    }

    private suspend fun collectBoundary(id: String, vararg frames: String): List<ProviderStreamEvent> {
        val case = S305ReplayFixture.case(id)
        val events = mutableListOf<ProviderStreamEvent>()
        ProviderReplayPeer(ProviderReplayReply.sse(frames.toList())).use { peer ->
            S305ReplaySession(case, listOf(peer)).use { session ->
                val failure = session.call { events += it }
                assertTrue("partial output must not complete: $failure", failure is ProviderResult.Failure)
                if (id == "openai") assertS305Failure(ProviderFailureCode.STREAM_TRUNCATED, failure)
            }
        }
        return events
    }
}

private fun List<ProviderStreamEvent>.reasoningItem() = filterIsInstance<ProviderStreamEvent.ResponsesMetadata>()
    .single { it.kind == ResponsesMetadataKind.REASONING_ITEM }

internal fun s305Ack() = ProviderReplayReply.sse(
    listOf(
        """{"type":"response.completed","response":{"status":"completed","output_text":"ack"}}""",
    ),
)

internal fun assertS305Failure(expected: ProviderFailureCode, result: ProviderResult<*>) {
    assertEquals(ProviderResult.Failure(ProviderFailure(expected)), result)
}
