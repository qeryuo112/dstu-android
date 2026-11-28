package com.deepstudent.services.providers.data

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.CodexRequestAuth
import com.deepstudent.services.providers.api.CodexRequestAuthPort
import com.deepstudent.services.providers.api.OpenAiResponsesRoutes
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
import com.deepstudent.services.providers.api.ProviderResult
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

/** App-owned I/O; no Responses bearer can be routed to an arbitrary Codex host. */
class OpenAiResponsesProviderFactory(
    private val network: NetworkTransport,
    override val key: ProviderAdapterKey,
    private val logger: Logger = NoopLogger,
    private val allowLoopback: Boolean = false,
    private val codexAuth: CodexRequestAuthPort? = null,
    private val version: String = "0.11.0",
) : ProviderAdapterFactory {
    override suspend fun create(resolved: ResolvedProviderConfig, credential: ProviderCredential?): ProviderStreamPort {
        if (resolved.protocol != ProviderProtocol.OPENAI_RESPONSES || !OpenAiResponsesRoutes.supports(resolved.providerType)) {
            throw responsesFailure(ProviderFailureCode.UNSUPPORTED_PROTOCOL)
        }
        if (key != ProviderAdapterKey(resolved.providerType, resolved.protocol, resolved.adapterId)) {
            throw responsesFailure(ProviderFailureCode.INVALID_CONFIGURATION)
        }
        val codex = resolved.providerType == "openai_codex"
        if (codex != (resolved.authMode == AuthMode.OPENAI_CODEX_OAUTH) || resolved.authMode == AuthMode.X_API_KEY) {
            throw responsesFailure(ProviderFailureCode.INVALID_AUTH_MODE)
        }
        if (codex && resolved.endpoint != OpenAiResponsesRoutes.CODEX_ENDPOINT) {
            throw responsesFailure(ProviderFailureCode.INVALID_ENDPOINT)
        }
        EndpointValidation.requireResponsesEndpoint(resolved.endpoint, allowLoopback)
        return ResponsesProviderPort(ResponsesHttpExchange(network, resolved), resolved, credential, codexAuth, version, logger)
    }
}

private class ResponsesProviderPort(
    private val exchange: ResponsesHttpExchange,
    private val resolved: ResolvedProviderConfig,
    private val credential: ProviderCredential?,
    private val codexAuth: CodexRequestAuthPort?,
    private val version: String,
    private val logger: Logger,
) : ProviderStreamPort {
    private val historyScope = ResponsesHistoryScope(resolved)

    override suspend fun stream(
        request: ProviderAdapterRequest,
        cancellation: ProviderCancellation,
        events: ProviderEventSink,
    ): ProviderStreamResult {
        if (request.resolved != resolved) throw responsesFailure(ProviderFailureCode.INVALID_CONFIGURATION)
        val trace = ResponsesTrace(resolved.providerType, logger)
        trace.record("started")
        return try {
            val result = whileProviderActive(cancellation) {
                collect(historyScope.sanitize(request), cancellation, historyScope.annotate(events), trace)
            }
            cancellation.checkActive()
            events.emit(ProviderStreamEvent.Completed(result.stopReason, result.usage))
            trace.record("completed", result.stopReason.name)
            result
        } catch (cancelled: CancellationException) {
            trace.record("cancelled")
            throw cancelled
        } catch (failure: ProviderException) {
            if (failure.failure.code ==
                ProviderFailureCode.CANCELLED
            ) {
                trace.record("cancelled")
            } else {
                trace.record("failed", failure.failure.code.name)
            }
            throw failure
        }
    }

    private suspend fun collect(
        request: ProviderAdapterRequest,
        cancellation: ProviderCancellation,
        events: ProviderEventSink,
        trace: ResponsesTrace,
    ): ProviderStreamResult {
        val body = try {
            OpenAiResponsesRequest.build(request)
        } catch (_: IllegalArgumentException) {
            throw responsesFailure(ProviderFailureCode.INVALID_MODEL_PARAMETERS)
        }
        if (resolved.authMode != AuthMode.OPENAI_CODEX_OAUTH) {
            val headers = ResponsesRequestHeaders.byok(resolved, credential)
            return exchange.post(body, headers, events).requireSuccess()
        }
        val auth = codexAuth ?: throw responsesFailure(ProviderFailureCode.CREDENTIAL_UNAVAILABLE)
        val sessionId = codexSessionId(request.sessionId)
        val initial = auth.requestAuth(cancellation).credentialLease()
        val first = initial.use { postCodex(body, it, sessionId, cancellation, events) }
        if (!first.isUnauthorized()) return first.requireSuccess()
        trace.record("refreshing", "UNAUTHORIZED")
        val refreshed = auth.refreshAfterUnauthorized(initial.generation, cancellation).credentialLease()
        val second = refreshed.use { postCodex(body, it, sessionId, cancellation, events) }
        if (!second.isUnauthorized()) return second.requireSuccess()
        cancellation.checkActive()
        auth.markReauthenticationRequired(refreshed.generation)
        throw responsesFailure(ProviderFailureCode.REAUTHENTICATION_REQUIRED)
    }

    private suspend fun postCodex(
        body: ResponsesWireBody,
        lease: CodexRequestAuth,
        sessionId: String,
        cancellation: ProviderCancellation,
        events: ProviderEventSink,
    ): ResponsesExchange {
        cancellation.checkActive()
        val headers = ResponsesRequestHeaders.codex(resolved, lease, sessionId, version)
        return exchange.post(body, headers, events)
    }
}

private fun ProviderResult<CodexRequestAuth>.credentialLease(): CodexRequestAuth = when (this) {
    is ProviderResult.Success -> value
    is ProviderResult.Failure -> throw ProviderException(failure)
    ProviderResult.Cancelled -> throw responsesFailure(ProviderFailureCode.CANCELLED)
}

private suspend fun ProviderCancellation.checkActive() {
    currentCoroutineContext().ensureActive()
    if (isCancelled()) throw CancellationException("provider call cancelled")
}

/** The narrow flag can be set without cancelling the caller's Job; also stop when the peer sends no frames. */
private suspend fun <T> whileProviderActive(cancellation: ProviderCancellation, action: suspend () -> T): T = coroutineScope {
    cancellation.checkActive()
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

private const val CANCEL_POLL_MILLIS = 25L

internal fun responsesFailure(code: ProviderFailureCode) = ProviderException(ProviderFailure(code))

private class ResponsesTrace(private val providerType: String, private val logger: Logger) {
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
            logger.log(LogRecord(level, "providers.openai_responses.$phase", correlationId = correlationId, fields = fields.build()))
        }
    }
}
