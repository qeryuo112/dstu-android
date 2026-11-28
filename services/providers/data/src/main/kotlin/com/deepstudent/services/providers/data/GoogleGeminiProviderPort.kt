package com.deepstudent.services.providers.data

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.network.NetworkError
import com.deepstudent.platform.network.NetworkException
import com.deepstudent.platform.network.NetworkHeader
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseLimits
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ProviderAdapterFactory
import com.deepstudent.services.providers.api.ProviderAdapterKey
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamPort
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ProviderUsage
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Receives Gemini 3 thought signatures for unit-owned persistence. */
fun interface GeminiThoughtSignatureSink {
    fun accept(signature: String)
}

/**
 * Google/Gemini `google_generate_content` adapter. Key modes map to the frozen
 * wire contract: the credential becomes the encoded `key` query plus the
 * `x-goog-api-key` header; the URL, its query and the key never appear in
 * diagnostics. Signatures and images come from the unit-owned ports.
 */
class GoogleGeminiProviderFactory(
    private val network: NetworkTransport,
    providerType: String,
    private val logger: Logger = NoopLogger,
    private val allowLoopback: Boolean = false,
    private val native: GeminiNativePorts = GeminiNativePorts(),
    private val appVersion: String = "0.11.0",
) : ProviderAdapterFactory {
    override val key: ProviderAdapterKey = ProviderAdapterKey(
        providerType.trim().lowercase(),
        ProviderProtocol.GOOGLE_GENERATE_CONTENT,
        GOOGLE_ADAPTER_ID,
    )

    override suspend fun create(resolved: ResolvedProviderConfig, credential: ProviderCredential?): ProviderStreamPort {
        if (resolved.protocol != ProviderProtocol.GOOGLE_GENERATE_CONTENT || resolved.providerType != key.providerType) {
            throw failure(ProviderFailureCode.UNSUPPORTED_PROTOCOL)
        }
        if (resolved.adapterId != GOOGLE_ADAPTER_ID) throw failure(ProviderFailureCode.INVALID_CONFIGURATION)
        if (resolved.authMode !in setOf(AuthMode.NONE, AuthMode.X_API_KEY, AuthMode.BEARER_API_KEY)) {
            throw failure(ProviderFailureCode.INVALID_AUTH_MODE)
        }
        GoogleGeminiEndpoint.validate(resolved.endpoint, allowLoopback)
        return GoogleGeminiProviderPort(network, resolved, credential, logger, native, appVersion)
    }

    companion object {
        const val GOOGLE_ADAPTER_ID = "google"
    }
}

internal class GoogleGeminiProviderPort(
    private val network: NetworkTransport,
    private val resolved: ResolvedProviderConfig,
    private val credential: ProviderCredential?,
    private val logger: Logger,
    private val native: GeminiNativePorts,
    private val appVersion: String,
) : ProviderStreamPort {
    private val scope = GoogleGeminiScope(resolved)

    override suspend fun stream(
        request: ProviderAdapterRequest,
        cancellation: ProviderCancellation,
        events: ProviderEventSink,
    ): ProviderStreamResult {
        if (request.resolved != resolved) throw failure(ProviderFailureCode.INVALID_CONFIGURATION)
        checkActive(cancellation)
        val trace = Trace(resolved.providerType, logger)
        trace.record("started", listOf("protocol" to resolved.protocol.wireName))
        return try {
            val result = whileProviderActive(cancellation) { collect(request, cancellation, events, trace) }
            checkActive(cancellation)
            events.emit(ProviderStreamEvent.Completed(result.stopReason, result.usage))
            trace.record("completed", listOf("stopReason" to result.stopReason.name))
            result
        } catch (cancelled: CancellationException) {
            trace.record("cancelled")
            throw cancelled
        } catch (failure: ProviderException) {
            if (failure.failure.code == ProviderFailureCode.CANCELLED) {
                trace.record("cancelled")
            } else {
                trace.record("failed", listOf("reason" to trace.lastReason))
            }
            throw failure
        }
    }

    private suspend fun collect(
        request: ProviderAdapterRequest,
        cancellation: ProviderCancellation,
        events: ProviderEventSink,
        trace: Trace,
    ): ProviderStreamResult {
        val key = keyValue()
        val wire = buildWire(request, trace)
        val exchange = Exchange(wire, key, headers(key, request.sessionId), events, trace)
        return exchange.run(cancellation)
    }

    private fun buildWire(request: ProviderAdapterRequest, trace: Trace): GoogleGeminiConverter.Wire = try {
        val body = GoogleGeminiRequest(request, scope, native.signatures, native.images).body()
        GoogleGeminiConverter.build(resolved.profile.model, body, GoogleGeminiEndpoint.route(resolved.endpoint))
    } catch (build: GoogleGeminiBuildException) {
        trace.reason(build.kind)
        throw failure(ProviderFailureCode.INVALID_MODEL_PARAMETERS)
    } catch (illegal: IllegalArgumentException) {
        trace.reason("invalid_request")
        throw failure(ProviderFailureCode.INVALID_MODEL_PARAMETERS)
    }

    private fun headers(key: String?, sessionId: String?): List<NetworkHeader> {
        val headers = mutableListOf(NetworkHeader("Content-Type", "application/json"))
        key?.let { headers += NetworkHeader("x-goog-api-key", it) }
        OpenCodeGoCatalog.appendIdentity(headers, resolved, sessionId, appVersion)
        RelayHeaders.mergeConfigured(headers, resolved.headers)
        return headers
    }

    /** Credential bytes become the x-goog-api-key header and the encoded query key once. */
    private fun keyValue(): String? = when (resolved.authMode) {
        AuthMode.NONE -> null

        AuthMode.X_API_KEY, AuthMode.BEARER_API_KEY -> credential?.use { bytes ->
            val value = bytes.decodeToString()
            if (value.isEmpty() || value.any { it != '\t' && it !in ' '..'~' }) {
                throw failure(ProviderFailureCode.AUTHENTICATION)
            }
            value
        } ?: throw failure(ProviderFailureCode.CREDENTIAL_UNAVAILABLE)

        else -> throw failure(ProviderFailureCode.INVALID_AUTH_MODE)
    }

    private suspend fun checkActive(cancellation: ProviderCancellation) {
        currentCoroutineContext().ensureActive()
        if (cancellation.isCancelled()) throw CancellationException("provider call cancelled")
    }

    private suspend fun <T> whileProviderActive(cancellation: ProviderCancellation, action: suspend () -> T): T = coroutineScope {
        checkActive(cancellation)
        val operation = async { action() }
        val watch = launch {
            while (isActive) {
                if (cancellation.isCancelled()) {
                    operation.cancel(CancellationException("provider call cancelled"))
                    break
                }
                delay(CANCEL_POLL_MILLIS)
            }
        }
        try {
            operation.await()
        } finally {
            watch.cancel()
        }
    }

    private inner class Exchange(
        wire: GoogleGeminiConverter.Wire,
        key: String?,
        headers: List<NetworkHeader>,
        private val events: ProviderEventSink,
        private val trace: Trace,
    ) {
        private val decoder = GoogleGeminiStreamDecoder()
        private val request = NetworkRequest(
            GoogleGeminiEndpoint.withKey(wire.url, key),
            "POST",
            headers,
            wire.body.literal().toByteArray(Charsets.UTF_8),
        )
        private val text = StringBuilder()
        private var reasoningLength = 0
        private var toolCallCount = 0
        private var toolCallSeen = false
        private var usage: ProviderUsage? = null
        private var httpStatus: Int? = null
        private var blocked: GoogleGeminiStreamEvent.Blocked? = null

        /** Frozen `onThoughtSignature` overwrites: the latest one is the turn's signature. */
        private var lastSignature: String? = null

        suspend fun run(cancellation: ProviderCancellation): ProviderStreamResult {
            val end = try {
                network.streamSse(
                    request,
                    SseLimits(stopAfterEvent = { decoder.terminal }),
                    onResponse = { head: ResponseHead -> httpStatus = head.statusCode },
                ) { frame ->
                    checkActive(cancellation)
                    decoder.accept(frame.data).forEach { apply(it) }
                }
            } catch (networkFailure: NetworkException) {
                currentCoroutineContext().ensureActive()
                if (blocked != null) throw blockedFailure()
                throw networkFailure(networkFailure)
            }
            checkActive(cancellation)
            return finish(end)
        }

        private suspend fun apply(event: GoogleGeminiStreamEvent) {
            when (event) {
                is GoogleGeminiStreamEvent.Text -> {
                    text.append(event.value)
                    events.emit(ProviderStreamEvent.TextDelta(event.value))
                }

                is GoogleGeminiStreamEvent.Reasoning -> {
                    reasoningLength += event.value.length
                    events.emit(ProviderStreamEvent.ReasoningDelta(event.value))
                }

                is GoogleGeminiStreamEvent.ToolCall -> {
                    toolCallCount += 1
                    toolCallSeen = true
                    events.emit(
                        ProviderStreamEvent.ToolCallDelta(
                            index = event.index,
                            id = event.id,
                            type = "function",
                            name = event.name,
                            argumentsFragment = event.argumentsSnapshot,
                            argumentsComplete = true,
                        ),
                    )
                }

                is GoogleGeminiStreamEvent.Signature -> {
                    lastSignature = event.value
                    native.signatureSink?.accept(event.value)
                }

                is GoogleGeminiStreamEvent.Usage -> applyUsage(event)

                is GoogleGeminiStreamEvent.Blocked -> if (blocked == null) blocked = event

                GoogleGeminiStreamEvent.Done -> Unit
            }
        }

        private suspend fun applyUsage(event: GoogleGeminiStreamEvent.Usage) {
            val input = event.prompt.takeIf { it >= 0 }
            val output = event.completion.takeIf { it >= 0 }
            val total = consistentTotal(input, output, event.total)
            val usage = ProviderUsage(
                inputTokens = input,
                cachedInputTokens = event.cached?.takeIf { it >= 0 },
                outputTokens = output,
                reasoningTokens = event.thoughts?.takeIf { it >= 0 },
                totalTokens = total,
            )
            if (!usage.known) return
            this.usage = usage
            events.emit(ProviderStreamEvent.UsageReported(usage))
        }

        private fun consistentTotal(input: Long?, output: Long?, total: Long): Long? {
            if (input == null || output == null || input > Long.MAX_VALUE - output) return null
            return total.takeIf { it == input + output }
        }

        private fun finish(end: SseEnd): ProviderStreamResult {
            if (end == SseEnd.REJECTED_HTTP_STATUS) throw httpFailure(httpStatus)
            blocked?.let { throw blockedFailure() }
            if (end != SseEnd.COMPLETE || !decoder.terminal) throw truncated()
            if (text.isEmpty() && reasoningLength == 0 && toolCallCount == 0) {
                trace.reason("empty_response")
                throw failure(ProviderFailureCode.PROVIDER_ERROR)
            }
            val stop = if (toolCallSeen) ProviderStopReason.TOOL_CALL else ProviderStopReason.STOP
            // Only a legal terminal reaches here; failures and cancellation never build history.
            return ProviderStreamResult(text.toString(), usage, stop, geminiNativeHistory(scope.value, lastSignature))
        }

        private fun blockedFailure(): ProviderException {
            val blocked = checkNotNull(this.blocked)
            trace.reason("safety_${blocked.type}")
            return failure(ProviderFailureCode.PROVIDER_ERROR)
        }

        private fun httpFailure(status: Int?): ProviderException {
            trace.reason("http_${status ?: 0}")
            return when (status) {
                401, 403 -> failure(ProviderFailureCode.AUTHENTICATION)
                407 -> failure(ProviderFailureCode.PROXY_AUTHENTICATION)
                429 -> failure(ProviderFailureCode.RATE_LIMITED)
                else -> failure(ProviderFailureCode.PROVIDER_ERROR)
            }
        }

        private fun networkFailure(networkFailure: NetworkException): ProviderException {
            trace.reason(networkFailure.error.name.lowercase())
            return when (networkFailure.error) {
                NetworkError.PROXY_AUTHENTICATION -> failure(ProviderFailureCode.PROXY_AUTHENTICATION)

                NetworkError.CONNECTION -> failure(
                    if (httpStatus in 200..299) ProviderFailureCode.STREAM_TRUNCATED else ProviderFailureCode.NETWORK,
                )

                NetworkError.SSE_TOO_LARGE, NetworkError.SSE_INVALID_UTF8, NetworkError.SSE_INVALID_CONTENT_TYPE,
                NetworkError.RESPONSE_TOO_LARGE,
                -> failure(ProviderFailureCode.PROVIDER_ERROR)

                else -> failure(ProviderFailureCode.NETWORK)
            }
        }

        private fun truncated() = failure(ProviderFailureCode.STREAM_TRUNCATED)
    }

    internal class Trace(private val providerType: String, private val logger: Logger) {
        private val correlationId = CorrelationId.parse(UUID.randomUUID().toString())
        private val started = System.nanoTime()
        var lastReason: String = "provider_error"
            private set

        fun reason(code: String) {
            lastReason = code
        }

        fun record(phase: String, fields: List<Pair<String, String>> = emptyList()) {
            val builder = JsonValue.Object.Builder()
                .put("owner", "services/providers")
                .put("category", providerType)
                .put("phase", phase)
                .put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0))
            if (phase == "failed") builder.put("reason", lastReason)
            fields.forEach { (key, value) -> builder.put(key, value) }
            val level = if (phase == "failed") LogLevel.WARN else LogLevel.INFO
            runCatching {
                logger.log(LogRecord(level, "providers.$providerType.$phase", correlationId = correlationId, fields = builder.build()))
            }
        }
    }
}

private const val CANCEL_POLL_MILLIS = 25L

private fun failure(code: ProviderFailureCode) = ProviderException(ProviderFailure(code))
