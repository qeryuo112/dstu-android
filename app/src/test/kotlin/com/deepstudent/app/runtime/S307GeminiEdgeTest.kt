package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderAdapterLocator
import com.deepstudent.services.providers.api.ProviderCallAccess
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderCredentialStore
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderRawConfig
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.backend.ProviderConfigResolver
import com.deepstudent.services.providers.backend.ProviderRawConfigDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

private val edgeCase = S307ReplayFixture.case("google-basic-thinking")

class S307GeminiErrorCancelTest {
    @Test
    fun mapsHttpStatusRejectionsToFixedFailures() = runBlocking {
        val statuses = mapOf(
            400 to ProviderFailureCode.PROVIDER_ERROR,
            401 to ProviderFailureCode.AUTHENTICATION,
            403 to ProviderFailureCode.AUTHENTICATION,
            429 to ProviderFailureCode.RATE_LIMITED,
        )
        for (case in listOf(edgeCase, S307ReplayFixture.case("gemini-provider"))) {
            verifyRejections(case, statuses)
        }
    }

    private suspend fun verifyRejections(case: S307Case, statuses: Map<Int, ProviderFailureCode>) {
        for ((status, expected) in statuses) {
            ProviderReplayPeer(ProviderReplayReply.rejection(status)).use { peer ->
                S307ReplaySession(case, listOf(peer)).use { session ->
                    assertS307Failure(expected, session.call { error("rejections emit no events") })
                    assertEquals(1, session.requests)
                    assertTrue(session.records.any { it.event == "providers.${case.provider}.failed" })
                    session.assertRedacted()
                }
            }
        }
    }

    @Test
    fun safetyBlocksKeepFragmentsAndNeverComplete() = runBlocking {
        verifyBlockedCase("google-error-prompt-blocked", "safety_prompt_blocked")
        verifyBlockedCase("google-error-finish-safety", "safety_content_blocked")
        verifyBlockedCase("google-error-finish-max-tokens", "safety_provider_error")
    }

    private suspend fun verifyBlockedCase(id: String, reason: String) {
        val case = S307ReplayFixture.case(id)
        val oracle = checkNotNull(case.stream).array("events").map { it as JsonValue.Object }
        ProviderReplayPeer(case.reply()).use { peer ->
            S307ReplaySession(case, listOf(peer)).use { session ->
                assertS307Failure(ProviderFailureCode.PROVIDER_ERROR, session.call())
                val actual = session.sequence.map(::s307Event)
                assertEquals(oracle.takeWhile { it.text("kind") != "blocked" }, actual)
                assertFalse(session.sequence.any { it.event is ProviderStreamEvent.Completed })
                assertTrue("blocked stream did not close the peer", peer.disconnected())
                assertTrue(
                    "missing $reason diagnostic",
                    session.records.any {
                        it.event == "providers.google.failed" && (it.fields["reason"] as? JsonValue.Text)?.value == reason
                    },
                )
                session.assertRedacted()
            }
        }
    }

    @Test
    fun emptyTerminalPayloadIsADistinguishableFailure() = runBlocking {
        val frames = listOf(
            """{"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":0,"totalTokenCount":5}}""",
            """{"candidates":[{"finishReason":"STOP"}]}""",
        )
        ProviderReplayPeer(ProviderReplayReply.sse(frames)).use { peer ->
            S307ReplaySession(edgeCase, listOf(peer)).use { session ->
                assertS307Failure(ProviderFailureCode.PROVIDER_ERROR, session.call { assertTrue(it is ProviderStreamEvent.UsageReported) })
                assertTrue(
                    "missing empty_response diagnostic",
                    session.records.any {
                        it.event == "providers.google.failed" && (it.fields["reason"] as? JsonValue.Text)?.value == "empty_response"
                    },
                )
                assertFalse(session.sequence.any { it.event is ProviderStreamEvent.Completed })
                session.assertRedacted()
            }
        }
    }

    @Test
    fun missingTerminalMarkerIsTruncation() = runBlocking {
        val frames = listOf("""{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}""")
        ProviderReplayPeer(ProviderReplayReply.sse(frames)).use { peer ->
            S307ReplaySession(edgeCase, listOf(peer)).use { session ->
                assertS307Failure(ProviderFailureCode.STREAM_TRUNCATED, session.call())
                assertFalse(session.sequence.any { it.event is ProviderStreamEvent.Completed })
                session.assertRedacted()
            }
        }
    }

    @Test
    fun preCancelledCallsNeverOpenASocket() = runBlocking {
        S307ReplaySession(edgeCase, emptyList()).use { session ->
            assertEquals(ProviderResult.Cancelled, session.call(cancellation = ProviderCancellation { true }))
            assertEquals(0, session.requests)
            session.assertRedacted(expectDiagnostics = false)
        }
    }

    @Test
    fun flagCancellationClosesTheOpenPeer() = runBlocking {
        for (case in listOf(edgeCase, S307ReplayFixture.case("gemini-provider"))) verifyFlagCancellation(case)
    }

    private suspend fun verifyFlagCancellation(case: S307Case) {
        val initial = """{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}"""
        ProviderReplayPeer(ProviderReplayReply.sse(listOf(initial), keepOpen = true)).use { peer ->
            S307ReplaySession(case, listOf(peer)).use { session ->
                val stop = AtomicBoolean()
                coroutineScope {
                    val received = CompletableDeferred<Unit>()
                    val call = async {
                        session.call(cancellation = ProviderCancellation(stop::get)) { received.complete(Unit) }
                    }
                    peer.captured()
                    withTimeout(2_000) { received.await() }
                    stop.set(true)
                    assertEquals(ProviderResult.Cancelled, call.await())
                }
                assertTrue("cancellation didn't close the peer socket", peer.disconnected())
                assertFalse(session.sequence.any { it.event is ProviderStreamEvent.Completed })
                assertTrue(session.records.any { it.event == "providers.${case.provider}.cancelled" })
                session.assertRedacted()
            }
        }
    }
}

class S307GeminiSetupTest {
    @Test
    fun unregisteredProtocolsFailBeforeAnyNetworkAccess() = runBlocking {
        val access = ProviderCallAccess(
            ProviderCredentialStore {
                ProviderResult.Success(ProviderCredential.fromUtf8(S307ReplayFixture.token.toCharArray()))
            },
            ProviderAdapterLocator { _ -> null },
        )
        val result = withTimeout(5_000) {
            ProviderConfigResolver().call(
                edgeCase.selection(),
                edgeCase.providerMessages(),
                edgeCase.tools,
                null,
                ProviderCancellation { false },
                access,
                ProviderEventSink {},
            )
        }
        assertEquals(ProviderResult.Failure(ProviderFailure(ProviderFailureCode.PROTOCOL_ADAPTER_UNAVAILABLE)), result)
    }

    @Test
    fun insecureEndpointsAreRejectedAtResolution() {
        val selection = ProviderRawConfigDecoder.decodeSelection(
            ProviderRawConfig(selectionJson(baseUrl = "http://generativelanguage.googleapis.com/v1beta").literal()),
        ).s307Failure(ProviderFailureCode.INVALID_ENDPOINT)
        assertEquals(ProviderResult.Failure(ProviderFailure(ProviderFailureCode.INVALID_ENDPOINT)), selection)
    }

    @Test
    fun invalidGeminiApiVersionFailsRouteResolution() = runBlocking {
        val selection = ProviderRawConfigDecoder.decodeSelection(
            ProviderRawConfig(selectionJson(extraVersion = "v2").literal()),
        ).s307Success()
        assertEquals(
            ProviderResult.Failure(ProviderFailure(ProviderFailureCode.INVALID_MODEL_PARAMETERS)),
            ProviderConfigResolver().resolve(selection),
        )
    }

    private fun selectionJson(baseUrl: String? = null, extraVersion: String? = null): JsonValue.Object {
        val vendor = JsonValue.Object.Builder()
            .put("id", "fixture-vendor").put("providerType", "google").put("name", "google")
            .put("baseUrl", baseUrl ?: "https://generativelanguage.googleapis.com/v1beta")
            .put("authMode", "x_api_key")
            .put("credentialKey", JsonValue.text("provider.chat.s307-fixture"))
            .put("headers", JsonValue.Object.EMPTY).build()
        val extra = JsonValue.Object.Builder().put("gemini_api_version", extraVersion ?: "v1beta")
        val profile = JsonValue.Object.Builder()
            .put("id", "fixture-profile").put("vendorId", "fixture-vendor")
            .put("model", "gemini-2.5-flash").put("label", "gemini-2.5-flash")
            .put("adapter", "google").put("providerScope", "google")
            .put("enabled", true).put("status", "active").put("isBuiltin", false)
            .put(
                "parameters",
                JsonValue.Object.Builder()
                    .put("maxOutputTokens", 2_048).put("temperature", JsonValue.Number.raw("0.25"))
                    .put("extraBody", extra.build()).build(),
            )
            .put(
                "reasoning",
                JsonValue.obj(
                    "isReasoning" to JsonValue.bool(true),
                    "thinkingEnabled" to JsonValue.bool(true),
                    "thinkingBudget" to JsonValue.integer(128),
                ),
            )
            .put(
                "features",
                JsonValue.obj(
                    "reasoning" to JsonValue.bool(true),
                    "embeddings" to JsonValue.bool(false),
                    "imageGeneration" to JsonValue.bool(false),
                    "reranking" to JsonValue.bool(false),
                    "favorite" to JsonValue.bool(false),
                ),
            )
            .put(
                "capabilities",
                JsonValue.obj(
                    "text" to JsonValue.bool(true),
                    "streaming" to JsonValue.bool(true),
                    "tools" to JsonValue.bool(true),
                    "images" to JsonValue.bool(true),
                    "audio" to JsonValue.bool(false),
                    "video" to JsonValue.bool(false),
                ),
            )
            .build()
        return JsonValue.obj("vendor" to vendor, "profile" to profile)
    }
}

private fun assertS307Failure(expected: ProviderFailureCode, result: ProviderResult<*>) {
    assertEquals(ProviderResult.Failure(ProviderFailure(expected)), result as? ProviderResult.Failure)
}

private fun ProviderResult<*>.s307Failure(expected: ProviderFailureCode): ProviderResult<*> {
    assertEquals(ProviderResult.Failure(ProviderFailure(expected)), this)
    return this
}
