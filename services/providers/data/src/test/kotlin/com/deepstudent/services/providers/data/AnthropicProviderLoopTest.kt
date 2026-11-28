package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.HttpResponse
import com.deepstudent.platform.network.NetworkLog
import com.deepstudent.platform.network.NetworkPhase
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.OkHttpNetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.platform.network.SseLimits
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.CredentialReference
import com.deepstudent.services.providers.api.ModelAdapter
import com.deepstudent.services.providers.api.ModelFeatures
import com.deepstudent.services.providers.api.ModelParameters
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ModelRuntime
import com.deepstudent.services.providers.api.ProviderAdapterKey
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderHeader
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderProtocolSource
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderRoute
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ReasoningFlags
import com.deepstudent.services.providers.api.ReasoningParameters
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException

/**
 * Local loopback only: the real OkHttp transport posts to a raw socket peer on
 * 127.0.0.1, exercising the actual request wire, fixed rejections, in-stream
 * failures and real socket closure on cancellation. No external network.
 */
private const val LOOP_SECRET = "s306-loop-key"

class AnthropicProviderLoopTest {

    @Test
    fun completionPostsTheWireRequestAndClosesTheCredential(): Unit = runBlocking {
        val frames = listOf(
            """{"type":"message_start","message":{"id":"m","usage":{"input_tokens":4,"output_tokens":0}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ack"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""",
            """{"type":"message_stop"}""",
        )
        AnthropicLoopPeer.sse(frames).use { peer ->
            val buffers = mutableListOf<ByteArray>()
            loopSession(peer, buffers).use { session ->
                val events = mutableListOf<ProviderStreamEvent>()
                val value = session.call { events += it }.successOrThrow()
                assertEquals("ack", value.text)
                assertEquals(4L, value.usage?.inputTokens)
                assertEquals(ProviderStopReason.STOP, value.stopReason)
                assertEquals(
                    ProviderStreamEvent.Completed(ProviderStopReason.STOP, value.usage),
                    events.last(),
                )
                val captured = peer.captured()
                assertEquals("POST", captured.method)
                assertEquals("/v1/messages", captured.path)
                assertEquals(LOOP_SECRET, captured.headers["x-api-key"])
                assertEquals("2023-06-01", captured.headers["anthropic-version"])
                assertEquals("application/json", captured.headers["content-type"])
                assertEquals("s306", captured.headers["x-fixture-provider"])
                assertEquals(session.model, ResponsesJson.text(JsonValue.parseObject(captured.body)["model"]))
                assertTrue("credential bytes must be wiped", buffers.all { bytes -> bytes.all { it == 0.toByte() } })
                assertTrue("diagnostics must exist", session.logRecords.isNotEmpty())
                assertFalse("secrets never reach diagnostics", session.logRecords.any { it.toString().contains(LOOP_SECRET) })
            }
        }
    }

    @Test
    fun httpRejectionsMapToFixedCodes(): Unit = runBlocking {
        val expected = mapOf(
            400 to ProviderFailureCode.PROVIDER_ERROR,
            401 to ProviderFailureCode.AUTHENTICATION,
            403 to ProviderFailureCode.AUTHENTICATION,
            429 to ProviderFailureCode.RATE_LIMITED,
        )
        for ((status, code) in expected) {
            AnthropicLoopPeer.rejection(status).use { peer ->
                loopSession(peer).use { session ->
                    val result = session.call { }
                    assertEquals(ProviderResult.Failure(ProviderFailure(code)), result)
                    assertEquals(1, session.requests)
                }
            }
        }
    }

    @Test
    fun preCancelledCallsNeverOpenASocket(): Unit = runBlocking {
        AnthropicLoopPeer.sse(listOf("""{"type":"message_stop"}""")).use { peer ->
            loopSession(peer).use { session ->
                val result = session.call(cancellation = ProviderCancellation { true }) { }
                assertEquals(ProviderResult.Cancelled::class, result::class)
                assertEquals(0, session.requests)
            }
        }
    }

    @Test
    fun userCancellationClosesTheRealSocketWithoutCompletion(): Unit = runBlocking {
        AnthropicLoopPeer.holdOpen(
            listOf("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}"""),
        ).use { peer ->
            loopSession(peer).use { session ->
                val stop = AtomicBoolean()
                val events = mutableListOf<ProviderStreamEvent>()
                val received = CompletableDeferred<Unit>()
                val result = coroutineScope {
                    val call = async {
                        session.call(cancellation = ProviderCancellation(stop::get)) {
                            events += it
                            received.complete(Unit)
                        }
                    }
                    peer.captured()
                    withTimeout(2_000) { received.await() }
                    stop.set(true)
                    call.await()
                }
                assertTrue(
                    "cancellation must not fail the call: $result phases=${session.phases}",
                    result is ProviderResult.Cancelled,
                )
                assertTrue("cancel must close the peer socket", peer.disconnected())
                assertFalse(events.any { it is ProviderStreamEvent.Completed })
            }
        }
    }

    @Test
    fun factoryPreflightRejectsWrongProtocolAuthOrEndpoint(): Unit = runBlocking {
        val factory =
            AnthropicProviderFactory(
                OkHttpNetworkTransport(),
                ProviderAdapterKey("anthropic", ProviderProtocol.ANTHROPIC_MESSAGES, "anthropic"),
            )
        val mismatched = resolvedConfig(protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS)
        assertFactoryFailure(factory, resolvedConfig(), ProviderCredential.fromUtf8(LOOP_SECRET.toCharArray()), null)
        assertFactoryFailure(
            factory,
            mismatched,
            ProviderCredential.fromUtf8(LOOP_SECRET.toCharArray()),
            ProviderFailureCode.UNSUPPORTED_PROTOCOL,
        )
        assertFactoryFailure(factory, resolvedConfig(authMode = AuthMode.NONE), null, ProviderFailureCode.INVALID_AUTH_MODE)
        assertFactoryFailure(
            factory,
            resolvedConfig(insecure = true),
            ProviderCredential.fromUtf8(LOOP_SECRET.toCharArray()),
            ProviderFailureCode.INSECURE_ENDPOINT,
        )
        Unit
    }

    private suspend fun assertFactoryFailure(
        factory: AnthropicProviderFactory,
        resolved: ResolvedProviderConfig,
        credential: ProviderCredential?,
        expected: ProviderFailureCode?,
    ) {
        val failure = runCatching { factory.create(resolved, credential) }.exceptionOrNull() as? ProviderException
        if (expected == null) {
            assertTrue("valid route must construct a port", failure == null)
        } else {
            assertEquals(expected, failure?.failure?.code)
        }
        credential?.close()
    }

    private fun loopSession(peer: AnthropicLoopPeer, buffers: MutableList<ByteArray> = mutableListOf()): AnthropicLoopSession =
        AnthropicLoopSession(peer, buffers, resolvedConfig())

    private class AnthropicLoopSession(
        private val peer: AnthropicLoopPeer,
        private val buffers: MutableList<ByteArray>,
        private val resolvedProvider: ResolvedProviderConfig,
    ) : AutoCloseable {
        internal val logRecords = mutableListOf<com.deepstudent.core.common.logging.LogRecord>()
        internal val phases = mutableListOf<NetworkPhase>()
        private val network = RedirectingNetwork(GOLDEN_URL, peer, OkHttpNetworkTransport(NetworkLog { phases += it.phase }))
        val requests: Int get() = network.requests
        val model = "claude-haiku-4-5"

        private val factory = AnthropicProviderFactory(
            network,
            ProviderAdapterKey("anthropic", ProviderProtocol.ANTHROPIC_MESSAGES, "anthropic"),
            RecordingLogger(logRecords),
        )

        suspend fun call(
            cancellation: ProviderCancellation = ProviderCancellation { false },
            onEvent: suspend (ProviderStreamEvent) -> Unit,
        ): ProviderResult<ProviderStreamResult> = withTimeout(5_000) {
            val credential = ProviderCredential.takeOwnership(LOOP_SECRET.toByteArray().also(buffers::add))
            try {
                val port = factory.create(resolvedProvider, credential)
                val request =
                    ProviderAdapterRequest(resolvedProvider, listOf(ProviderMessage(ProviderRole.USER, "loop")), emptyList(), null)
                ProviderResult.Success(port.stream(request, cancellation, ProviderEventSink(onEvent)))
            } catch (cancelled: CancellationException) {
                ProviderResult.Cancelled
            } catch (failure: ProviderException) {
                ProviderResult.Failure(failure.failure)
            } finally {
                credential.close()
            }
        }

        override fun close() = network.close()

        companion object {
            val GOLDEN_URL = "https://api.anthropic.com/v1/messages"
        }
    }

    private class RecordingLogger(private val sink: MutableList<com.deepstudent.core.common.logging.LogRecord>) :
        com.deepstudent.core.common.logging.Logger {
        override fun log(record: com.deepstudent.core.common.logging.LogRecord) {
            sink += record
        }
    }

    private class RedirectingNetwork(
        private val expectedUrl: String,
        private val peer: AnthropicLoopPeer,
        private val delegate: NetworkTransport,
    ) : NetworkTransport {
        private val count = java.util.concurrent.atomic.AtomicInteger()
        val requests: Int get() = count.get()

        override suspend fun execute(request: NetworkRequest, limits: HttpLimits): HttpResponse =
            delegate.execute(redirect(request), limits)

        override suspend fun streamSse(
            request: NetworkRequest,
            limits: SseLimits,
            onResponse: suspend (ResponseHead) -> Unit,
            onEvent: suspend (SseEvent) -> Unit,
        ): SseEnd = delegate.streamSse(redirect(request), limits, onResponse, onEvent)

        private fun redirect(request: NetworkRequest): NetworkRequest {
            check(expectedUrl == request.url) { "loop target differs from the resolved route" }
            count.incrementAndGet()
            return NetworkRequest(peer.origin + URI(request.url).rawPath, request.method, request.headers, request.body)
        }

        override fun close() = delegate.close()
    }

    private fun resolvedConfig(
        protocol: ProviderProtocol = ProviderProtocol.ANTHROPIC_MESSAGES,
        authMode: AuthMode = AuthMode.X_API_KEY,
        insecure: Boolean = false,
    ): ResolvedProviderConfig {
        val baseUrl = if (insecure) "http://api.anthropic.com/v1" else "https://api.anthropic.com/v1"
        val vendor = VendorConfig(
            VendorIdentity("fixture-vendor", "anthropic"),
            VendorConnection(baseUrl, authMode, CredentialReference("fixture.key")),
            headers = listOf(ProviderHeader("x-fixture-provider", "s306")),
        )
        val profile = ModelProfile(
            "fixture-profile",
            "fixture-vendor",
            "claude-haiku-4-5",
            providerScope = "anthropic",
            protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
            adapter = ModelAdapter.ANTHROPIC,
        )
        val runtime = ModelRuntime(
            reasoning = ReasoningParameters(flags = ReasoningFlags(thinkingEnabled = false)),
            parameters = ModelParameters(maxOutputTokens = 256, temperature = 0.7),
            features = ModelFeatures(reasoning = false),
        )
        val route = ProviderRoute(baseUrl, "$baseUrl/messages", authMode, vendor.credential, vendor.headers, "anthropic")
        return ResolvedProviderConfig(
            vendor,
            profile,
            runtime,
            "anthropic",
            "anthropic",
            protocol,
            ProviderProtocolSource.DEFAULT,
            route,
        )
    }
}

/** Minimal raw loopback peer for one request; hold-open mode watches the client close. */
internal class AnthropicLoopPeer private constructor(private val reply: String, private val holdOpen: Boolean) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val active = AtomicReference<Socket?>()
    private val closed = AtomicBoolean()
    private val capture = CompletableFuture<CapturedRequest>()
    private val disconnect = CompletableFuture<Boolean>()
    val origin = "http://127.0.0.1:${server.localPort}"
    private val worker = thread(name = "s3-06-loop-peer", isDaemon = true) { serve() }

    suspend fun captured(): CapturedRequest = withTimeout(5_000) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { capture.get(4, TimeUnit.SECONDS) }
    }

    suspend fun disconnected(): Boolean = withTimeout(5_000) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { disconnect.get(4, TimeUnit.SECONDS) }
    }

    private fun serve() {
        try {
            server.accept().use { socket ->
                active.set(socket)
                if (closed.get()) return
                socket.soTimeout = 6_000
                val input = socket.getInputStream().buffered()
                capture.complete(input.readRequest())
                socket.getOutputStream().write(reply.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                if (holdOpen) disconnect.complete(input.read() == -1)
            }
        } catch (failure: Exception) {
            capture.completeExceptionally(failure)
            disconnect.completeExceptionally(failure)
        }
    }

    override fun close() {
        closed.set(true)
        server.close()
        active.get()?.close()
        worker.join(7_000)
    }

    class CapturedRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun BufferedInputStream.readRequest(): CapturedRequest {
        val start = nextLine().split(' ')
        val headers = linkedMapOf<String, String>()
        repeat(64) {
            val header = nextLine()
            if (header.isEmpty()) {
                val body = readNBytes(headers.getValue("content-length").toInt()).decodeToString()
                return CapturedRequest(start[0], start[1], headers, body)
            }
            val separator = header.indexOf(':')
            headers[header.substring(0, separator).lowercase()] = header.substring(separator + 1).trim()
        }
        error("too many headers")
    }

    private fun BufferedInputStream.nextLine(): String {
        val bytes = ByteArrayOutputStream()
        repeat(16_384) {
            val next = read()
            if (next < 0) error("unexpected EOF in request headers")
            if (next == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
            bytes.write(next)
        }
        error("request line exceeds the test budget")
    }

    companion object {
        fun sse(frames: List<String>): AnthropicLoopPeer {
            val body = frames.joinToString(separator = "") { "data: $it\n\n" }
            val bytes = body.toByteArray(Charsets.UTF_8)
            val reply = buildString {
                append("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n")
                append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                append(body)
            }
            return AnthropicLoopPeer(reply, holdOpen = false)
        }

        fun rejection(status: Int): AnthropicLoopPeer {
            val body = """{"error":"loop"}"""
            val reply = buildString {
                append("HTTP/1.1 $status Denied\r\nContent-Type: application/json\r\n")
                append("Content-Length: ${body.length}\r\nConnection: close\r\n\r\n")
                append(body)
            }
            return AnthropicLoopPeer(reply, holdOpen = false)
        }

        fun holdOpen(frames: List<String>): AnthropicLoopPeer {
            val bytes = frames.joinToString(separator = "") { "data: $it\n\n" }.toByteArray(Charsets.UTF_8)
            val body = bytes.decodeToString()
            val reply = buildString {
                append("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n")
                append("Transfer-Encoding: chunked\r\n\r\n${bytes.size.toString(16)}\r\n")
                append(body).append("\r\n")
            }
            return AnthropicLoopPeer(reply, holdOpen = true)
        }
    }
}

private fun ProviderResult<ProviderStreamResult>.successOrThrow(): ProviderStreamResult =
    (this as? ProviderResult.Success)?.value ?: error("loop call failed: $this")
