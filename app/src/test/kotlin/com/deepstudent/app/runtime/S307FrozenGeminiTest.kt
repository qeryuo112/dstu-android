package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.net.URI

@RunWith(Parameterized::class)
class S307FrozenRequestTest(private val id: String) {
    @Test
    fun postsExactFrozenRequestThroughTheGoogleRoute() = runBlocking {
        val case = S307ReplayFixture.case(id)
        ProviderReplayPeer(s307Ack()).use { peer ->
            S307ReplaySession(case, listOf(peer)).use { session ->
                assertEquals("ack", session.call().s307Success().text)
                val captured = peer.captured()
                session.assertWireRequest(captured)
                session.assertRedacted()
                assertEquals(1, session.requests)
                s307Capture(
                    case,
                    "request",
                    JsonValue.Object.Builder()
                        .put("path", checkNotNull(URI(case.requestUrl).rawPath))
                        .put("query", "alt=sse")
                        .put("auth", "x_api_key")
                        .put("body", JsonValue.parseObject(captured.body))
                        .build(),
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<String>> = S307ReplayFixture.cases.map { arrayOf(it.id) }
    }
}

@RunWith(Parameterized::class)
class S307FrozenStreamTest(private val id: String) {
    @Test
    fun deliversFrozenEventsInOrderAndClosesOnTerminal() = runBlocking {
        val case = S307ReplayFixture.case(id)
        ProviderReplayPeer(case.reply()).use { peer ->
            S307ReplaySession(case, listOf(peer)).use { session ->
                val result = session.call().s307Success()
                val actual = session.sequence.map(::s307Event)
                val expected = checkNotNull(case.stream).array("events").map { it as JsonValue.Object }
                assertEquals(expected, actual)
                val text = expected.filter { it.text("kind") == "text" }.joinToString("") { checkNotNull(it.text("value")) }
                assertEquals(text, result.text)
                val expectedStop = if (expected.any { it.text("kind") == "tool" }) ProviderStopReason.TOOL_CALL else ProviderStopReason.STOP
                assertEquals(expectedStop, result.stopReason)
                val usage = expected.lastOrNull { it.text("kind") == "usage" }
                val actualUsage = result.usage?.let { s307Event(S307SequenceItem("event", ProviderStreamEvent.UsageReported(it), null)) }
                assertEquals(usage, actualUsage)
                assertEquals(result.usage, (checkNotNull(session.sequence.last().event) as ProviderStreamEvent.Completed).usage)
                assertEquals(expectedStop, (checkNotNull(session.sequence.last().event) as ProviderStreamEvent.Completed).stopReason)
                assertTrue("connection stayed open after Gemini terminal event", peer.disconnected())
                session.assertWireRequest(peer.captured())
                session.assertRedacted()
                s307Capture(
                    case,
                    "stream",
                    JsonValue.Object.Builder().put("events", JsonValue.array(actual)).put("peerClosed", true).build(),
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<String>> = S307ReplayFixture.cases
            .filter {
                it.stream != null && (it.stream.array("events").last() as JsonValue.Object).text("kind") == "completed"
            }
            .map { arrayOf(it.id) }
    }
}

class S307GeminiSignatureScopeTest {
    @Test
    fun crossTargetHistoryStripsSignaturesBeforeConversion() = runBlocking {
        val case = S307ReplayFixture.case("google-signature-history")
        val cross = checkNotNull(case.crossTarget)
        ProviderReplayPeer(s307Ack()).use { peer ->
            S307ReplaySession(case, listOf(peer), crossTarget = true).use { session ->
                assertEquals("ack", session.call().s307Success().text)
                val captured = peer.captured()
                assertEquals("POST", captured.method)
                assertEquals(
                    checkNotNull(URI(case.requestUrl).rawPath) + "?" + checkNotNull(URI(case.requestUrl).rawQuery),
                    captured.path,
                )
                assertEquals(checkNotNull(cross["body"]), JsonValue.parseObject(captured.body))
                assertTrue("signature leaked across targets", !captured.body.contains("fixture-signature"))
                session.assertRedacted()
            }
        }
    }
}

internal fun S307ReplaySession.assertWireRequest(captured: ProviderReplayCapture) {
    assertEquals("POST", captured.method)
    assertEquals(
        checkNotNull(URI(case.requestUrl).rawPath) + "?" + checkNotNull(URI(case.requestUrl).rawQuery),
        captured.path,
    )
    val wantedHeaders = case.request.obj("headers")
    for ((name, value) in wantedHeaders.asMap()) assertEquals(name, (value as JsonValue.Text).value, captured.headers[name])
    assertEquals(checkNotNull(case.request["body"]), JsonValue.parseObject(captured.body))
}

private fun s307Ack() = ProviderReplayReply.sse(
    listOf("""{"candidates":[{"content":{"parts":[{"text":"ack"}]},"finishReason":"STOP"}]}"""),
)
