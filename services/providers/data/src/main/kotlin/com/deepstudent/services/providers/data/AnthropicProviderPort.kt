package com.deepstudent.services.providers.data

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ProviderAdapterFactory
import com.deepstudent.services.providers.api.ProviderAdapterKey
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderNativeHistory
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamPort
import com.deepstudent.services.providers.api.ProviderStreamResult
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

/**
 * Unregistered S3.06 candidate: the Anthropic Messages protocol port
 * (`providers/anthropic.ts` AnthropicAdapter.build_request at
 * e6b2657fd59014a73f5bbb1d14c2db732cf23e87). Construction happens only after
 * config preflight; the port owns one resolved route, closes the real socket
 * on cancellation, and keeps thinking signatures in the dedicated replay
 * sidecar instead of the shared sealed stream API. The factory never registers
 * itself — the formal locator wiring stays with the main thread.
 */
class AnthropicProviderFactory(
    private val network: NetworkTransport,
    override val key: ProviderAdapterKey,
    private val logger: Logger = NoopLogger,
    private val allowLoopback: Boolean = false,
    private val appVersion: String = "0.11.0",
) : ProviderAdapterFactory {
    override suspend fun create(resolved: ResolvedProviderConfig, credential: ProviderCredential?): ProviderStreamPort {
        if (resolved.protocol != ProviderProtocol.ANTHROPIC_MESSAGES) throw anthropicFailure(ProviderFailureCode.UNSUPPORTED_PROTOCOL)
        if (key != ProviderAdapterKey(resolved.providerType, resolved.protocol, resolved.adapterId)) {
            throw anthropicFailure(ProviderFailureCode.INVALID_CONFIGURATION)
        }
        if (resolved.authMode != AuthMode.X_API_KEY) throw anthropicFailure(ProviderFailureCode.INVALID_AUTH_MODE)
        val url = AnthropicEndpoint.requireValid(resolved, allowLoopback)
        return AnthropicProviderPort(network, resolved, credential, url, logger, appVersion)
    }
}

/**
 * Concrete Anthropic port. Callers that hold this type may inject an
 * [AnthropicRequestSidecar]; the plain [ProviderStreamPort] path stays
 * sidecar-free so generic runtimes never touch private replay state.
 */
class AnthropicProviderPort internal constructor(
    private val network: NetworkTransport,
    private val resolved: ResolvedProviderConfig,
    private val credential: ProviderCredential?,
    private val url: String,
    private val logger: Logger,
    private val appVersion: String,
) : ProviderStreamPort,
    AnthropicReplayCarrier {
    private val historyScope = AnthropicHistoryScope.compute(resolved)
    private var lastCapture: AnthropicReplayCapture? = null

    override suspend fun stream(
        request: ProviderAdapterRequest,
        cancellation: ProviderCancellation,
        events: ProviderEventSink,
    ): ProviderStreamResult = stream(request, cancellation, events, null)

    suspend fun stream(
        request: ProviderAdapterRequest,
        cancellation: ProviderCancellation,
        events: ProviderEventSink,
        sidecar: AnthropicRequestSidecar?,
    ): ProviderStreamResult {
        if (request.resolved != resolved) throw anthropicFailure(ProviderFailureCode.INVALID_CONFIGURATION)
        lastCapture = null
        val trace = AnthropicTrace(resolved.providerType, logger)
        trace.record("started")
        return try {
            val result = whileAnthropicActive(cancellation) { collect(request, cancellation, events, sidecar) }
            cancellation.checkAnthropicActive()
            events.emit(ProviderStreamEvent.Completed(result.first.stopReason, result.first.usage))
            lastCapture = capture(result.second)
            trace.record("completed", result.first.stopReason.name)
            withNativeHistory(result.first, result.second)
        } catch (cancelled: CancellationException) {
            trace.record("cancelled")
            throw cancelled
        } catch (failure: ProviderException) {
            if (failure.failure.code == ProviderFailureCode.CANCELLED) {
                trace.record("cancelled")
            } else {
                trace.record("failed", failure.failure.code.name)
            }
            throw failure
        }
    }

    override fun replayCapture(): AnthropicReplayCapture? = lastCapture

    private suspend fun collect(
        request: ProviderAdapterRequest,
        cancellation: ProviderCancellation,
        events: ProviderEventSink,
        sidecar: AnthropicRequestSidecar?,
    ): Pair<ProviderStreamResult, List<JsonValue>> {
        val body = try {
            AnthropicRequestBody.build(request, sidecar, historyScope)
        } catch (_: IllegalArgumentException) {
            throw anthropicFailure(ProviderFailureCode.INVALID_MODEL_PARAMETERS)
        }
        val headers = AnthropicRequestHeaders.byok(resolved, credential, body.betaFeatures).toMutableList()
        OpenCodeGoCatalog.appendIdentity(headers, resolved, request.sessionId, appVersion)
        cancellation.checkAnthropicActive()
        val exchange = AnthropicHttpExchange(network).post(body.json, url, headers, events)
        return exchange.requireSuccess()
    }

    private fun capture(blocks: List<JsonValue>): AnthropicReplayCapture? {
        if (blocks.isEmpty()) return null
        return AnthropicReplayCapture(historyScope, mapOf(0 to JsonValue.array(blocks).literal()))
    }

    /**
     * Successful turns hand the ordered native blocks back through the shared
     * result; the scope binds them to this exact target. A payload over the
     * bounded DTO limit is dropped instead of failing an otherwise good turn.
     */
    private fun withNativeHistory(result: ProviderStreamResult, blocks: List<JsonValue>): ProviderStreamResult {
        if (blocks.isEmpty()) return result
        val payload = JsonValue.array(blocks).literal()
        if (payload.length > ProviderNativeHistory.MAX_PAYLOAD_CHARS) return result
        val history = ProviderNativeHistory(ProviderProtocol.ANTHROPIC_MESSAGES, historyScope, payload)
        return result.copy(nativeHistory = history)
    }
}

private suspend fun ProviderCancellation.checkAnthropicActive() {
    currentCoroutineContext().ensureActive()
    if (isCancelled()) throw CancellationException("provider call cancelled")
}

/** The narrow flag can be set without cancelling the caller's Job; also stop on silent peers. */
private suspend fun <T> whileAnthropicActive(cancellation: ProviderCancellation, action: suspend () -> T): T = coroutineScope {
    cancellation.checkAnthropicActive()
    val operation = async { action() }
    val watch = launch {
        while (isActive) {
            if (cancellation.isCancelled()) {
                operation.cancel(CancellationException("provider call cancelled"))
                break
            }
            delay(ANTHROPIC_CANCEL_POLL_MILLIS)
        }
    }
    try {
        operation.await()
    } finally {
        watch.cancel()
    }
}

private const val ANTHROPIC_CANCEL_POLL_MILLIS = 25L

/** Structured, redacted trace: phase, fixed code, elapsed time, correlation id. */
private class AnthropicTrace(private val providerType: String, private val logger: Logger) {
    private val correlationId = CorrelationId.parse(UUID.randomUUID().toString())
    private val started = System.nanoTime()

    fun record(phase: String, code: String? = null) {
        val fields = JsonValue.Object.Builder()
            .put("owner", "services/providers")
            .put("category", providerType)
            .put("phase", phase)
            .put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0))
        code?.let { fields.put("reason", it) }
        val level = if (phase == "failed") LogLevel.WARN else LogLevel.INFO
        runCatching {
            logger.log(LogRecord(level, "providers.anthropic.$phase", correlationId = correlationId, fields = fields.build()))
        }
    }
}
