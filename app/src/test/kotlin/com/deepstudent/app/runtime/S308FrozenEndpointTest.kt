package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.network.NetworkPhase
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ProviderUsage
import com.deepstudent.services.providers.backend.ProviderConfigResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * S3.08 common setup: five members (custom/general/opencode_go/openrouter/ollama) replayed
 * against the frozen desktop oracle over real loopback HTTP through the relay boundary.
 */
@RunWith(Parameterized::class)
class S308FrozenRequestTest(private val id: String) {
    @Test
    fun request() = runBlocking {
        val case = S308EndpointFixture.case(id)
        val reply = ProviderReplayReply.sse(S308EndpointFixture.streamFrames)
        ProviderReplayPeer(reply).use { peer ->
            S308EndpointSession(case, peer).use { session ->
                val result = session.call().success()
                assertEquals("哈", result.text)
                assertEquals(ProviderStopReason.TOOL_CALL, result.stopReason)
                val captured = session.assertRecordedRequest()
                session.assertNoLeakedSecrets()
                writeS308Receipt(
                    case,
                    "request",
                    JsonValue.Object.Builder()
                        .put("path", captured.path).put("method", captured.method)
                        .put("body", JsonValue.parseObject(captured.body)).build(),
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = S308EndpointFixture.chatCases.map { arrayOf(it.id) }
    }
}

@RunWith(Parameterized::class)
class S308FrozenStreamTest(private val id: String) {
    @Test
    fun stream() = runBlocking {
        val case = S308EndpointFixture.case(id)
        ProviderReplayPeer(ProviderReplayReply.sse(S308EndpointFixture.streamFrames, keepOpen = true)).use { peer ->
            S308EndpointSession(case, peer).use { session ->
                val received = mutableListOf<ProviderStreamEvent>()
                val result = session.call { received += it }.success()
                val expected = S308EndpointFixture.streamEvents
                assertEquals(expected, received.map(::s308DesktopEvent))
                val expectedText = expected.filter { it.text("kind") == "text" }.joinToString("") { it.text("value") }
                assertEquals(expectedText, result.text)
                assertEquals(ProviderStopReason.TOOL_CALL, result.stopReason)
                assertEquals(result.usage, (received.last() as ProviderStreamEvent.Completed).usage)
                assertEquals(1, session.requests)
                assertTrue("[DONE] must close the live peer without waiting for EOF", peer.disconnected())
                assertEquals(listOf(NetworkPhase.STARTED, NetworkPhase.COMPLETED), session.phases.toList())
                session.assertNoLeakedSecrets()
                writeS308Receipt(
                    case,
                    "stream",
                    JsonValue.Object.Builder()
                        .put("events", JsonValue.array(received.map(::s308DesktopEvent)))
                        .put("finish", result.stopReason.name).put("doneClosedSocket", true).build(),
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = S308EndpointFixture.chatCases.map { arrayOf(it.id) }
    }
}

@RunWith(Parameterized::class)
class S308ProviderFailureTest(private val id: String) {
    @Test
    fun errorAndCancel() = runBlocking {
        val case = S308EndpointFixture.case(id)
        noIoBeforePreflight(case)
        for ((status, code) in PROBES) {
            rejected(case, ProviderReplayReply.rejection(status), code)
        }
        val streamError = ProviderReplayReply.sse(listOf("""{"error":{"message":"$REPLAY_PRIVATE_DETAIL"}}"""), keepOpen = true)
        rejected(case, streamError, ProviderFailureCode.PROVIDER_ERROR)
        cancelAfterFirstEvent(case)
        writeS308Receipt(
            case,
            "error-cancel",
            JsonValue.Object.Builder()
                .put("http", JsonValue.Object.from(PROBES.associate { (status, code) -> status.toString() to JsonValue.text(code.name) }))
                .put("sseError", ProviderFailureCode.PROVIDER_ERROR.name).put("sseErrorSocketClosed", true)
                .put("cancelAfterFirstChunkSocketClosed", true).put("cancelReceivedCompletion", false)
                .put("credentialsErased", true)
                .put("preflightNetworkCalls", 0).put("preflightCredentialReads", 0).build(),
        )
    }

    private suspend fun noIoBeforePreflight(case: S308EndpointCase) {
        ProviderReplayPeer(ProviderReplayReply.rejection(400)).use { peer ->
            S308EndpointSession(case, peer).use { session ->
                assertEquals(ProviderFailureCode.MISSING_CREDENTIAL_REFERENCE, session.call(auth = "x_api_key").failureCode())
                assertEquals(ProviderResult.Cancelled, session.call(cancellation = ProviderCancellation { true }))
                assertEquals(0, session.requests)
                assertEquals(0, session.credentialReads)
            }
        }
    }

    private suspend fun rejected(case: S308EndpointCase, reply: ProviderReplayReply, expected: ProviderFailureCode) {
        ProviderReplayPeer(reply).use { peer ->
            S308EndpointSession(case, peer).use { session ->
                val received = mutableListOf<ProviderStreamEvent>()
                assertEquals(expected, session.call { received += it }.failureCode())
                assertTrue("upstream failure emitted a normal event", received.isEmpty())
                assertEquals("failure must not retry", 1, session.requests)
                assertEquals(if (case.auth == "none") 0 else 1, session.credentialReads)
                if (reply.keepOpen) assertTrue("in-band error left the server connection open", peer.disconnected())
                session.assertNoLeakedSecrets()
            }
        }
    }

    private suspend fun cancelAfterFirstEvent(case: S308EndpointCase) = kotlinx.coroutines.coroutineScope {
        val delta = """{"choices":[{"delta":{"content":"part"},"finish_reason":null}]}"""
        ProviderReplayPeer(ProviderReplayReply.sse(listOf(delta), keepOpen = true)).use { peer ->
            S308EndpointSession(case, peer).use { session ->
                val first = CompletableDeferred<String>()
                val events = mutableListOf<ProviderStreamEvent>()
                val job = launch {
                    val result = session.call(emit = { event ->
                        events += event
                        if (event is ProviderStreamEvent.TextDelta) first.complete(event.text)
                    })
                    error("cancelled transport returned instead of propagating cancellation: $result")
                }
                assertEquals("part", withTimeout(4_000) { first.await() })
                assertTrue(job.isActive)
                withTimeout(4_000) { job.cancelAndJoin() }
                assertTrue("socket was not closed on cancellation", peer.disconnected())
                assertFalse("cancelled stream emitted completion", events.any { it is ProviderStreamEvent.Completed })
                assertEquals(listOf(NetworkPhase.STARTED, NetworkPhase.CANCELLED), session.phases.toList())
                assertEquals(1, session.requests)
                assertEquals(if (case.auth == "none") 0 else 1, session.credentialReads)
                session.assertNoLeakedSecrets()
            }
        }
    }

    companion object {
        private val PROBES = listOf(
            400 to ProviderFailureCode.PROVIDER_ERROR,
            401 to ProviderFailureCode.AUTHENTICATION,
            429 to ProviderFailureCode.RATE_LIMITED,
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = S308EndpointFixture.chatCases.map { arrayOf(it.id) }
    }
}

/** Explicit protocol selection must resolve the frozen routes without any silent chat fallback. */
class S308FrozenRouteTest {
    @Test
    fun routes() = runBlocking {
        for (case in S308EndpointFixture.routeCases) {
            val selection = case.selection()
            val resolved = ProviderConfigResolver().resolve(selection)
            assertTrue("route $case.id failed: $resolved", resolved is ProviderResult.Success)
            val route = (resolved as ProviderResult.Success).value
            assertEquals(case.protocol, route.protocol.wireName)
            assertEquals(case.adapter, route.adapterId)
            when (case.provider) {
                "opencode_go" -> {
                    assertTrue("expected the frozen go base swap", route.baseUrl == case.goBase)
                    assertTrue(route.endpoint.startsWith(checkNotNull(case.goBase)))
                }

                else -> assertTrue(route.endpoint == "${route.baseUrl}/responses")
            }
            writeS308Receipt(
                case,
                "route",
                JsonValue.Object.Builder()
                    .put("protocol", route.protocol.wireName).put("adapter", route.adapterId)
                    .put("explicitNotDowngraded", true).build(),
            )
        }
    }
}

private fun ProviderResult<ProviderStreamResult>.success(): ProviderStreamResult =
    (this as? ProviderResult.Success)?.value ?: error("provider stream failed: $this")

private fun ProviderResult<ProviderStreamResult>.failureCode(): ProviderFailureCode =
    (this as? ProviderResult.Failure)?.failure?.code ?: error("provider unexpectedly did not fail: $this")

private fun s308DesktopEvent(event: ProviderStreamEvent): JsonValue.Object = when (event) {
    is ProviderStreamEvent.TextDelta -> recorded("text", JsonValue.text(event.text))
    is ProviderStreamEvent.ReasoningDelta -> recorded("reasoning", JsonValue.text(event.text))
    is ProviderStreamEvent.ToolCallDelta -> recorded("tool", JsonValue.text(checkNotNull(event.rawJson)))
    is ProviderStreamEvent.ResponsesMetadata -> error("Chat Completions emitted Responses metadata")
    is ProviderStreamEvent.UsageReported -> recorded("usage", rawUsage(event.usage))
    is ProviderStreamEvent.Completed -> recorded("completed", null)
}

private fun recorded(kind: String, value: JsonValue?): JsonValue.Object =
    JsonValue.Object.Builder().put("kind", kind).putIfNotNull("value", value).build()

private fun rawUsage(usage: ProviderUsage): JsonValue.Object = JsonValue.Object.Builder()
    .count("prompt_tokens", usage.inputTokens)
    .count("completion_tokens", usage.outputTokens)
    .count("total_tokens", usage.totalTokens)
    .put(
        "prompt_tokens_details",
        JsonValue.Object.Builder().count("cached_tokens", usage.cachedInputTokens)
            .count("cache_write_tokens", usage.cacheWriteInputTokens).build(),
    )
    .put("completion_tokens_details", JsonValue.Object.Builder().count("reasoning_tokens", usage.reasoningTokens).build())
    .build()

private fun JsonValue.Object.Builder.count(key: String, value: Long?) = putIfNotNull(key, value?.let(JsonValue::integer))
