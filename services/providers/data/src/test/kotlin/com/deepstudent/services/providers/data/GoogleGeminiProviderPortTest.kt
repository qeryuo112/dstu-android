package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.platform.network.SseLimits
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

private class GeminiRecordingNetwork(
    private val frames: List<String>,
    private val end: SseEnd = SseEnd.COMPLETE,
    private val status: Int = 200,
    private val holdOpen: Boolean = false,
) : NetworkTransport {
    val requests = CopyOnWriteArrayList<NetworkRequest>()

    override suspend fun execute(request: NetworkRequest, limits: com.deepstudent.platform.network.HttpLimits) =
        error("unexpected non-streaming call")

    override suspend fun streamSse(
        request: NetworkRequest,
        limits: SseLimits,
        onResponse: suspend (ResponseHead) -> Unit,
        onEvent: suspend (SseEvent) -> Unit,
    ): SseEnd {
        requests += request
        onResponse(ResponseHead(status, emptyList()))
        val stop = limits.stopAfterEvent
        for (frame in frames) {
            val event = SseEvent(frame, "", "")
            onEvent(event)
            if (stop != null && stop(event)) return SseEnd.COMPLETE
        }
        if (holdOpen) {
            kotlinx.coroutines.delay(60_000)
            return SseEnd.COMPLETE
        }
        return end
    }

    override fun close() = Unit
}

private class GeminiCollectingLogger : Logger {
    val records = CopyOnWriteArrayList<LogRecord>()
    override fun log(record: LogRecord) {
        records += record
    }
}

class GoogleGeminiProviderPortTest {
    @Test
    fun `port maps the google credential into header and encoded key query`() = runBlocking {
        val frames = listOf(
            """{"candidates":[{"content":{"parts":[{"text":"回答"}]},"finishReason":"STOP"}]}""",
        )
        val network = GeminiRecordingNetwork(frames)
        val resolved = GoogleGeminiFixtures.resolved("gemini-2.5-flash", baseUrl = "https://generativelanguage.example/v1beta")
        val emitted = mutableListOf<ProviderStreamEvent>()
        val result = GoogleGeminiProviderFactory(network, "google")
            .create(resolved, ProviderCredential.fromUtf8("k/1 +2".toCharArray()))
            .stream(request(resolved), ProviderCancellation { false }) { emitted += it }
        assertEquals("回答", result.text)
        val sent = network.requests.single()
        assertTrue(sent.url.endsWith(":streamGenerateContent?alt=sse&key=k%2F1+%2B2"))
        assertTrue(sent.url.contains("/v1beta/models/gemini-2.5-flash:streamGenerateContent"))
        assertEquals("k/1 +2", sent.headers.first { it.name == "x-goog-api-key" }.value)
        assertTrue(sent.headers.any { it.name == "Content-Type" && it.value == "application/json" })
        assertTrue(emitted.last() is ProviderStreamEvent.Completed)
    }

    @Test
    fun `auth none sends no credential at all`() = runBlocking {
        val frames = listOf("""{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}""")
        val network = GeminiRecordingNetwork(frames)
        // Auth NONE with no credential must not send a key at all.
        val noneResolved = GoogleGeminiFixtures.resolved("gemini-2.5-flash").let { config ->
            config.copy(route = config.route.copy(authMode = com.deepstudent.services.providers.api.AuthMode.NONE, credential = null))
        }
        GoogleGeminiProviderFactory(network, "google")
            .create(noneResolved, null)
            .stream(request(noneResolved), ProviderCancellation { false }) {}
        val sent = network.requests.single()
        assertTrue(!sent.url.contains("key="))
        assertTrue(sent.headers.none { it.name == "x-goog-api-key" })
    }

    @Test
    fun `missing credentials fail before the socket opens`() = runBlocking {
        val network = GeminiRecordingNetwork(emptyList())
        val resolved = GoogleGeminiFixtures.resolved("gemini-2.5-flash")
        runCatching {
            GoogleGeminiProviderFactory(network, "google")
                .create(resolved, null)
                .stream(request(resolved), ProviderCancellation { false }) {}
        }.onSuccess { error("accepted a missing credential") }
            .onFailure { assertEquals(ProviderFailureCode.CREDENTIAL_UNAVAILABLE, (it as ProviderException).failure.code) }
        assertEquals(0, network.requests.size)
    }

    @Test
    fun `safety blocks stop the read and never complete`() = runBlocking {
        val frames = listOf(
            """{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}""",
            """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            """{"candidates":[{"finishReason":"STOP"}]}""",
        )
        val logger = GeminiCollectingLogger()
        val network = GeminiRecordingNetwork(frames)
        val resolved = GoogleGeminiFixtures.resolved("gemini-2.5-flash")
        val emitted = mutableListOf<ProviderStreamEvent>()
        runCatching {
            GoogleGeminiProviderFactory(network, "google", logger)
                .create(resolved, ProviderCredential.fromUtf8("k".toCharArray()))
                .stream(request(resolved), ProviderCancellation { false }) { emitted += it }
        }.onSuccess { error("accepted a blocked stream") }
            .onFailure { assertEquals(ProviderFailureCode.PROVIDER_ERROR, (it as ProviderException).failure.code) }
        assertEquals(listOf("partial"), emitted.filterIsInstance<ProviderStreamEvent.TextDelta>().map { it.text })
        assertTrue(emitted.none { it is ProviderStreamEvent.Completed })
        val failed = logger.records.single { it.event == "providers.google.failed" }
        assertEquals("safety_prompt_blocked", (failed.fields["reason"] as JsonValue.Text).value)
    }

    @Test
    fun `empty terminal payloads are a distinct fixed failure`() = runBlocking {
        val frames = listOf("""{"candidates":[{"finishReason":"STOP"}]}""")
        val logger = GeminiCollectingLogger()
        val network = GeminiRecordingNetwork(frames)
        val resolved = GoogleGeminiFixtures.resolved("gemini-2.5-flash")
        runCatching {
            GoogleGeminiProviderFactory(network, "google", logger)
                .create(resolved, ProviderCredential.fromUtf8("k".toCharArray()))
                .stream(request(resolved), ProviderCancellation { false }) {}
        }.onSuccess { error("accepted an empty stream") }
            .onFailure { assertEquals(ProviderFailureCode.PROVIDER_ERROR, (it as ProviderException).failure.code) }
        assertEquals(
            "empty_response",
            (
                logger.records.single {
                    it.event == "providers.google.failed"
                }.fields["reason"] as JsonValue.Text
                ).value,
        )
    }

    @Test
    fun `missing terminal markers are truncation`() = runBlocking {
        val network = GeminiRecordingNetwork(listOf("""{"candidates":[{"content":{"parts":[{"text":"p"}]}}]}"""))
        val resolved = GoogleGeminiFixtures.resolved("gemini-2.5-flash")
        runCatching {
            GoogleGeminiProviderFactory(network, "google")
                .create(resolved, ProviderCredential.fromUtf8("k".toCharArray()))
                .stream(request(resolved), ProviderCancellation { false }) {}
        }.onSuccess { error("accepted a truncated stream") }
            .onFailure { assertEquals(ProviderFailureCode.STREAM_TRUNCATED, (it as ProviderException).failure.code) }
        assertTrue(network.requests.single().url.contains(":streamGenerateContent?alt=sse&key="))
    }

    @Test
    fun `http rejections map to fixed failure codes`() = runBlocking {
        val cases = mapOf(
            401 to ProviderFailureCode.AUTHENTICATION,
            429 to ProviderFailureCode.RATE_LIMITED,
            500 to ProviderFailureCode.PROVIDER_ERROR,
        )
        for ((status, expected) in cases) {
            val network = GeminiRecordingNetwork(emptyList(), end = SseEnd.REJECTED_HTTP_STATUS, status = status)
            val resolved = GoogleGeminiFixtures.resolved("gemini-2.5-flash")
            runCatching {
                GoogleGeminiProviderFactory(network, "google")
                    .create(resolved, ProviderCredential.fromUtf8("k".toCharArray()))
                    .stream(request(resolved), ProviderCancellation { false }) {}
            }.onSuccess { error("accepted HTTP $status") }
                .onFailure { assertEquals(expected, (it as ProviderException).failure.code) }
        }
    }

    @Test
    fun `flag cancellation interrupts the stream without completion`() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val frames = listOf("""{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}""")
        val network = GeminiRecordingNetwork(frames, holdOpen = true)
        val resolved = GoogleGeminiFixtures.resolved("gemini-2.5-flash")
        val logger = GeminiCollectingLogger()
        val factory = GoogleGeminiProviderFactory(network, "google", logger)
        val stop = java.util.concurrent.atomic.AtomicBoolean()
        kotlinx.coroutines.coroutineScope {
            val job = launch {
                try {
                    factory.create(resolved, ProviderCredential.fromUtf8("k".toCharArray()))
                        .stream(request(resolved), ProviderCancellation(stop::get)) { started.complete(Unit) }
                    error("cancelled stream returned a result")
                } catch (_: CancellationException) {
                    // expected
                }
            }
            started.await()
            stop.set(true)
            job.join()
        }
        assertTrue(logger.records.any { it.event == "providers.google.cancelled" })
    }

    @Test
    fun `tool snapshots arrive complete and push the stop reason to tool call`() = runBlocking {
        val frames = listOf(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"id":"fc-1","name":"lookup","args":{"q":"🧪"}}}]}}]}""",
            """{"candidates":[{"finishReason":"STOP"}]}""",
        )
        val network = GeminiRecordingNetwork(frames)
        val resolved = GoogleGeminiFixtures.resolved("gemini-2.5-flash")
        val emitted = mutableListOf<ProviderStreamEvent>()
        val result: ProviderStreamResult = GoogleGeminiProviderFactory(network, "google")
            .create(resolved, ProviderCredential.fromUtf8("k".toCharArray()))
            .stream(request(resolved), ProviderCancellation { false }) { emitted += it }
        val tool = emitted.filterIsInstance<ProviderStreamEvent.ToolCallDelta>().single()
        assertEquals(0, tool.index)
        assertEquals("fc-1", tool.id)
        assertEquals("function", tool.type)
        assertEquals("lookup", tool.name)
        assertEquals("""{"q":"🧪"}""", tool.argumentsFragment)
        assertEquals(true, tool.argumentsComplete)
        assertEquals(ProviderStopReason.TOOL_CALL, result.stopReason)
        assertEquals(ProviderStopReason.TOOL_CALL, (emitted.last() as ProviderStreamEvent.Completed).stopReason)
    }

    @Test
    fun `thought signatures reach the unit owned sink`() = runBlocking {
        val frames = listOf(
            """{"candidates":[{"content":{"parts":[{"thoughtSignature":"sig-1"},{"text":"a"}]},"thoughtSignature":"sig-2"}]}""",
            """{"candidates":[{"finishReason":"STOP"}]}""",
        )
        val network = GeminiRecordingNetwork(frames)
        val resolved = GoogleGeminiFixtures.resolved("gemini-2.5-flash")
        val signatures = mutableListOf<String>()
        val result = GoogleGeminiProviderFactory(network, "google", native = GeminiNativePorts(signatureSink = { signatures += it }))
            .create(resolved, ProviderCredential.fromUtf8("k".toCharArray()))
            .stream(request(resolved), ProviderCancellation { false }) {}
        assertEquals(listOf("sig-1", "sig-2"), signatures)
        assertEquals("""{"thoughtSignature":"sig-2"}""", result.nativeHistory?.payloadJson)
    }

    @Test
    fun `logs stay desensitized and carry correlation plus elapsed fields`() = runBlocking {
        val logger = GeminiCollectingLogger()
        val frames = listOf("""{"candidates":[{"content":{"parts":[{"text":"回答"}]},"finishReason":"STOP"}]}""")
        val network = GeminiRecordingNetwork(frames)
        val resolved = GoogleGeminiFixtures.resolved("gemini-2.5-flash")
        val secret = "credential-k"
        GoogleGeminiProviderFactory(network, "google", logger)
            .create(resolved, ProviderCredential.fromUtf8(secret.toCharArray()))
            .stream(request(resolved), ProviderCancellation { false }) {}
        assertTrue(logger.records.isNotEmpty())
        assertTrue(logger.records.all { it.correlationId != null && it.fields["elapsedMs"] != null })
        assertTrue(logger.records.none { record -> record.toString().contains(secret) })
        assertTrue(logger.records.none { record -> record.toString().contains("key=") })
        assertTrue(logger.records.none { record -> record.toString().contains("回答") })
    }

    @Test
    fun `mismatched resolved config and unsupported provider types fail fast`() = runBlocking {
        val network = GeminiRecordingNetwork(emptyList())
        val resolved = GoogleGeminiFixtures.resolved("gemini-2.5-flash")
        val factory = GoogleGeminiProviderFactory(network, "google")
        val mismatched: ProviderAdapterRequest = ProviderAdapterRequest(
            resolved.copy(providerType = "gemini"),
            emptyList(),
            emptyList(),
            null,
        )
        runCatching {
            factory.create(resolved, ProviderCredential.fromUtf8("k".toCharArray()))
                .stream(mismatched, ProviderCancellation { false }) {}
        }.onSuccess { error("accepted a mismatched config") }
            .onFailure { assertEquals(ProviderFailureCode.INVALID_CONFIGURATION, (it as ProviderException).failure.code) }
        runCatching {
            GoogleGeminiProviderFactory(network, "anthropic")
                .create(resolved, ProviderCredential.fromUtf8("k".toCharArray()))
        }.onSuccess { error("accepted a mismatched provider type") }
            .onFailure { assertEquals(ProviderFailureCode.UNSUPPORTED_PROTOCOL, (it as ProviderException).failure.code) }
        assertTrue(network.requests.isEmpty())
    }

    private fun request(resolved: com.deepstudent.services.providers.api.ResolvedProviderConfig) = ProviderAdapterRequest(
        resolved,
        listOf(ProviderMessage(ProviderRole.USER, "hi")),
        emptyList(),
        null,
    )
}
