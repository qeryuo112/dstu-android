package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.network.NetworkPhase
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.data.AnthropicReplayCapture
import com.deepstudent.services.providers.data.AnthropicReplayCarrier
import com.deepstudent.services.providers.data.AnthropicReplayState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.atomic.AtomicBoolean

/**
 * S3.06 frozen golden loop for the Anthropic protocol family: recorded request
 * bodies, stream event sequences and the fixed error/cancel matrix, replayed
 * through the real application resolver route and the unregistered candidate
 * factory. Normal recordings compare item by item; the documented Android
 * safety deviations (refusal/context/truncated tool fail instead of the
 * desktop's blocked-content wire fallback) are asserted explicitly.
 */
@RunWith(Parameterized::class)
class S306FrozenRequestTest(private val id: String) {
    private val case = S306ReplayFixture.case(id)

    @Test
    fun postsTheFrozenRequestThroughTheApplicationRoute() = runBlocking {
        ProviderReplayPeer(ProviderReplayReply.sse(listOf("""{"type":"message_stop"}"""))).use { peer ->
            S306ReplaySession(case, listOf(peer)).use { session ->
                val events = mutableListOf<ProviderStreamEvent>()
                val result = session.call(withSidecar = case.needsSidecar) { events += it }
                assertTrue("request case must complete: $result", result is ProviderResult.Success)
                assertTrue("request cases end with the explicit message_stop completion", events.last() is ProviderStreamEvent.Completed)
                case.assertWireRequest(peer.captured())
                session.assertRedacted()
                assertEquals(1, session.requests)
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<String>> = S306ReplayFixture.requestCases.map { arrayOf(it.id) }
    }
}

@RunWith(Parameterized::class)
class S306FrozenStreamTest(private val id: String) {
    private val case = S306ReplayFixture.case(id)
    private val stream = checkNotNull(case.streamJson)

    @Test
    fun deliversTheGoldenEventsAndEndsWithTheDocumentedBoundary() = runBlocking {
        ProviderReplayPeer(case.reply()).use { peer ->
            S306ReplaySession(case, listOf(peer)).use { session ->
                val events = mutableListOf<ProviderStreamEvent>()
                val result = session.call(withSidecar = case.needsSidecar) { events += it }
                val expected = stream.s306Array("androidEvents")
                assertEquals(case.id, expected, events.map(::s306Event))
                assertBoundary(result, events)
                assertTrue("terminal must close the peer connection", peer.disconnected())
                case.assertWireRequest(peer.captured())
                session.assertRedacted()
                assertReplayCapture(session)
            }
        }
    }

    private suspend fun assertBoundary(result: ProviderResult<ProviderStreamResult>, events: List<ProviderStreamEvent>) {
        val expected = stream.s306Text("androidResult") ?: error("androidResult")
        if (expected == "complete") {
            val success = result.s306Success()
            assertEquals(case.id, stream.s306Text("androidText").orEmpty(), success.text)
            assertEquals(case.id, stream.s306Text("androidStop"), success.stopReason.name)
            val completed = events.last() as ProviderStreamEvent.Completed
            assertEquals(success.stopReason, completed.stopReason)
            assertEquals(success.usage, completed.usage)
        } else {
            val failure = result as? ProviderResult.Failure ?: error("expected a fixed failure: $result")
            assertEquals(case.id, expected.removePrefix("fail:"), failure.failure.code.name)
            assertFalse("deviations never complete the stream", events.any { it is ProviderStreamEvent.Completed })
        }
    }

    private suspend fun assertReplayCapture(session: S306ReplaySession) {
        val expectedBlocks = (stream["replayBlocks"] as? JsonValue.Array)?.values.orEmpty()
        if (expectedBlocks.isEmpty()) return
        val capture = session.replayCaptureOrNull()
        checkNotNull(capture) { "same-target streams must expose their replay capture" }
        assertEquals(case.historyScope, capture.stateScope)
        assertEquals(JsonValue.array(expectedBlocks), JsonValue.parse(checkNotNull(capture.blocksByMessageIndex[0])))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<String>> = S306ReplayFixture.streamCases.map { arrayOf(it.id) }
    }
}

@RunWith(Parameterized::class)
class S306ErrorCancelTest(private val id: String) {
    private val case = S306ReplayFixture.case(id)

    @Test
    fun reportsRedactedRejectionsAndCancelsTheOpenSocket() = runBlocking {
        verifyRejections()
        verifyPreCancelled()
        verifyFlagCancellation()
        verifyCoroutineCancellation()
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
                S306ReplaySession(case, listOf(peer)).use { session ->
                    val result = session.call(withSidecar = case.needsSidecar) { error("rejections emit no events") }
                    assertEquals(ProviderResult.Failure(ProviderFailure(expected)), result)
                    assertEquals(1, session.requests)
                    session.assertRedacted()
                }
            }
        }
    }

    private suspend fun verifyPreCancelled() {
        S306ReplaySession(case, emptyList()).use { session ->
            val result = session.call(cancellation = ProviderCancellation { true }) { error("cancelled calls emit nothing") }
            assertEquals(ProviderResult.Cancelled, result)
            assertEquals(0, session.requests)
            session.assertRedacted()
        }
    }

    private suspend fun verifyFlagCancellation() {
        val frame = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}"""
        ProviderReplayPeer(ProviderReplayReply.sse(listOf(frame), keepOpen = true)).use { peer ->
            S306ReplaySession(case, listOf(peer)).use { session ->
                val stop = AtomicBoolean()
                val events = mutableListOf<ProviderStreamEvent>()
                val result = coroutineScope {
                    val received = CompletableDeferred<Unit>()
                    val call = async {
                        session.call(
                            cancellation = ProviderCancellation(stop::get),
                            withSidecar = case.needsSidecar,
                        ) { event ->
                            events += event
                            received.complete(Unit)
                        }
                    }
                    peer.captured()
                    kotlinx.coroutines.withTimeout(2_000) { received.await() }
                    stop.set(true)
                    call.await()
                }
                assertEquals(ProviderResult.Cancelled, result)
                assertTrue("cancel must close the peer socket", peer.disconnected())
                assertFalse(events.any { it is ProviderStreamEvent.Completed })
                assertEquals(listOf(NetworkPhase.STARTED, NetworkPhase.CANCELLED), session.phases.toList())
                session.assertRedacted()
            }
        }
    }

    private suspend fun verifyCoroutineCancellation() {
        ProviderReplayPeer(
            ProviderReplayReply.sse(
                listOf("""{"type":"message_start","message":{"id":"m","usage":{"input_tokens":1,"output_tokens":0}}}"""),
                keepOpen = true,
            ),
        ).use { peer ->
            S306ReplaySession(case, listOf(peer)).use { session ->
                coroutineScope {
                    val call = async { session.call(withSidecar = case.needsSidecar) { error("unexpected event") } }
                    peer.captured()
                    call.cancelAndJoin()
                }
                assertTrue("coroutine cancel must close the peer socket", peer.disconnected())
                session.assertRedacted()
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<String>> = listOf("manual-thinking-tools", "stream-complete-thinking-tool").map { arrayOf(it) }
    }
}

class S306ReplayBoundaryTest {
    @Test
    fun sameTargetReplayFeedsTheCapturedSignatureBack(): Unit = runBlocking {
        val streamCase = S306ReplayFixture.case("stream-complete-thinking-tool")
        val capture = captureFrom(streamCase)
        val toolCall = capturedToolCall(streamCase)
        val messages = listOf(
            ProviderMessage(ProviderRole.USER, "继续"),
            ProviderMessage(
                ProviderRole.ASSISTANT,
                "查一下",
                toolCallsJson = JsonValue.array(
                    listOf(
                        JsonValue.obj(
                            "id" to JsonValue.text(checkNotNull(toolCall.text("id"))),
                            "type" to JsonValue.text("function"),
                            "function" to checkNotNull(toolCall["function"]),
                        ),
                    ),
                ).literal(),
            ),
            ProviderMessage(ProviderRole.TOOL, "北京当前晴", toolCallId = checkNotNull(toolCall.text("id"))),
            ProviderMessage(ProviderRole.USER, "总结"),
        )
        ProviderReplayPeer(ProviderReplayReply.sse(listOf("""{"type":"message_stop"}"""))).use { peer ->
            S306ReplaySession(streamCase, listOf(peer)).use { session ->
                session.call(messages = messages, replay = capture.forMessage(1)).s306Success()
                val body = JsonValue.parseObject(peer.captured().body)
                val assistant = body.array("messages").map { it as JsonValue.Object }.single { it.text("role") == "assistant" }
                val blocks = assistant.array("content")!!.map { it as JsonValue.Object }
                assertEquals("thinking", blocks.first().text("type"))
                assertEquals("需要工具", blocks.first().text("thinking"))
                assertEquals("EqQBCgXYZ123", blocks.first().text("signature"))
                assertEquals("text", blocks[1].text("type"))
                assertEquals("查一下", blocks[1].text("text"))
                assertEquals("tool_use", blocks[2].text("type"))
                assertEquals("toolu_1", blocks[2].text("id"))
                val toolResult = body.array("messages").map { it as JsonValue.Object }
                    .single { (it.array("content")?.first() as? JsonValue.Object)?.text("type") == "tool_result" }
                assertEquals("toolu_1", toolResult.array("content")!!.first().let { (it as JsonValue.Object).text("tool_use_id") })
                session.assertRedacted()
            }
        }
    }

    @Test
    fun changingTheModelDropsThePrivateReplayState(): Unit = runBlocking {
        val streamCase = S306ReplayFixture.case("stream-complete-thinking-tool")
        val capture = captureFrom(streamCase)
        val target = streamCase.selection(model = "claude-sonnet-5")
        val messages = listOf(
            ProviderMessage(ProviderRole.USER, "继续"),
            ProviderMessage(ProviderRole.ASSISTANT, "查一下"),
            ProviderMessage(ProviderRole.USER, "总结"),
        )
        ProviderReplayPeer(ProviderReplayReply.sse(listOf("""{"type":"message_stop"}"""))).use { peer ->
            S306ReplaySession(streamCase, listOf(peer)).use { session ->
                session.call(selection = target, messages = messages, replay = capture.forMessage(1)).s306Success()
                val body = JsonValue.parseObject(peer.captured().body)
                val assistant = body.array("messages").map { it as JsonValue.Object }.single { it.text("role") == "assistant" }
                val blocks = assistant.array("content")!!.map { it as JsonValue.Object }
                assertEquals("text", blocks.first().text("type"))
                assertEquals("需要工具", blocks.first().text("text"))
                assertFalse("signature must not cross targets", body.literal().contains("EqQBCg"))
                assertFalse("redacted state must not cross targets", body.literal().contains("opaque_payload"))
                session.assertRedacted()
            }
        }
    }

    private fun AnthropicReplayCapture.forMessage(index: Int): AnthropicReplayState =
        AnthropicReplayState(stateScope, mapOf(index to checkNotNull(blocksByMessageIndex[0])))

    private suspend fun captureFrom(streamCase: S306ReplayCase): AnthropicReplayCapture {
        ProviderReplayPeer(streamCase.reply()).use { peer ->
            S306ReplaySession(streamCase, listOf(peer)).use { session ->
                val events = CompletableDeferred<Unit>()
                session.call(withSidecar = true) {
                    events.complete(Unit)
                }.s306Success()
                return checkNotNull(session.replayCaptureOrNull()) { "capture missing" }
            }
        }
    }

    private fun capturedToolCall(streamCase: S306ReplayCase): JsonValue.Object {
        val stream = checkNotNull(streamCase.streamJson)
        val tool = stream.s306Array("androidEvents").map { it as JsonValue.Object }
            .single { it.s306Text("kind") == "tool" }
        return tool["value"] as? JsonValue.Object ?: error("tool event payload")
    }
}
