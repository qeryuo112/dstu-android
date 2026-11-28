package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.HttpResponse
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.platform.network.SseLimits
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ProviderAdapterFactory
import com.deepstudent.services.providers.api.ProviderAdapterKey
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamPort
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Explicit-Responses candidates for the S3.08 members. They reuse the existing internal
 * non-Codex wire components (payload builder, history scope, BYOK headers, SSE exchange)
 * without touching the S3.05 provider whitelist, faking provider identities or leaking
 * account scope. Codex OAuth is not offered here.
 */
internal class S308ResponsesAdapterFactory(
    private val network: NetworkTransport,
    override val key: ProviderAdapterKey,
    private val logger: Logger = NoopLogger,
    private val allowLoopback: Boolean = false,
    private val version: String = "0.11.0",
) : ProviderAdapterFactory {
    override suspend fun create(resolved: ResolvedProviderConfig, credential: ProviderCredential?): ProviderStreamPort {
        if (resolved.protocol != ProviderProtocol.OPENAI_RESPONSES) throw responsesFailure(ProviderFailureCode.UNSUPPORTED_PROTOCOL)
        if (key != ProviderAdapterKey(resolved.providerType, resolved.protocol, resolved.adapterId)) {
            throw responsesFailure(ProviderFailureCode.INVALID_CONFIGURATION)
        }
        if (resolved.authMode == AuthMode.OPENAI_CODEX_OAUTH || resolved.authMode == AuthMode.X_API_KEY) {
            throw responsesFailure(ProviderFailureCode.INVALID_AUTH_MODE)
        }
        EndpointValidation.requireResponsesEndpoint(resolved.endpoint, allowLoopback)
        val session = RelaySession()
        val transport = if (OpenCodeGoCatalog.isOpencodeGoConfig(resolved.vendor.id, resolved.providerType, resolved.baseUrl)) {
            S308IdentityTransport(network, resolved, version, session)
        } else {
            network
        }
        return S308ResponsesPort(
            ResponsesHttpExchange(transport, resolved),
            ResponsesHistoryScope(resolved),
            resolved,
            credential,
            session,
            logger,
        )
    }
}

/** Applies OpenCode Go identity headers on Go roots; bodies pass through unchanged. */
internal class S308IdentityTransport(
    private val delegate: NetworkTransport,
    private val resolved: ResolvedProviderConfig,
    private val version: String,
    private val session: RelaySession,
) : NetworkTransport {
    private val openCodeGo: Boolean = OpenCodeGoCatalog.isOpencodeGoConfig(resolved.vendor.id, resolved.providerType, resolved.baseUrl)

    override suspend fun execute(request: NetworkRequest, limits: HttpLimits): HttpResponse =
        delegate.execute(applyIdentity(request), limits)

    override suspend fun streamSse(
        request: NetworkRequest,
        limits: SseLimits,
        onResponse: suspend (ResponseHead) -> Unit,
        onEvent: suspend (SseEvent) -> Unit,
    ): SseEnd = delegate.streamSse(applyIdentity(request), limits, onResponse, onEvent)

    override fun close() = Unit

    private fun applyIdentity(request: NetworkRequest): NetworkRequest {
        if (!openCodeGo) return request
        val headers = request.headers.toMutableList()
        val sessionValue = OpenCodeGoCatalog.sessionHeaderValue(session.value) ?: OpenCodeGoCatalog.TASK_SESSION_ID
        for ((name, value) in OpenCodeGoCatalog.identityHeaders(sessionValue, version)) {
            RelayHeaders.appendIfAbsent(headers, name, value)
        }
        return NetworkRequest(request.url, request.method, headers, request.body)
    }
}

internal class S308ResponsesPort(
    private val exchange: ResponsesHttpExchange,
    private val historyScope: ResponsesHistoryScope,
    private val resolved: ResolvedProviderConfig,
    private val credential: ProviderCredential?,
    private val session: RelaySession,
    private val logger: Logger = NoopLogger,
) : ProviderStreamPort {
    override suspend fun stream(
        request: ProviderAdapterRequest,
        cancellation: ProviderCancellation,
        events: ProviderEventSink,
    ): ProviderStreamResult {
        if (request.resolved != resolved) throw responsesFailure(ProviderFailureCode.INVALID_CONFIGURATION)
        session.value = request.sessionId
        val trace = S308Trace(logger, resolved.providerType, "providers.s308.responses")
        trace.record(S308Trace.STAGE_STARTED, fields = JsonValue.Object.Builder().put("protocol", resolved.protocol.wireName).build())
        return try {
            val result = whileS308ProviderActive(cancellation) {
                collect(historyScope.sanitize(request), historyScope.annotate(events), trace)
            }
            checkS308Active(cancellation)
            events.emit(ProviderStreamEvent.Completed(result.stopReason, result.usage))
            trace.record(S308Trace.STAGE_COMPLETED, result.stopReason.name)
            result
        } catch (cancelled: CancellationException) {
            trace.record(S308Trace.STAGE_CANCELLED)
            throw cancelled
        } catch (failure: ProviderException) {
            val code = failure.failure.code
            trace.record(if (code == ProviderFailureCode.CANCELLED) S308Trace.STAGE_CANCELLED else S308Trace.STAGE_FAILED, code.name)
            throw failure
        }
    }

    private suspend fun collect(request: ProviderAdapterRequest, events: ProviderEventSink, trace: S308Trace): ProviderStreamResult {
        val body = try {
            OpenAiResponsesRequest.build(request)
        } catch (_: IllegalArgumentException) {
            throw responsesFailure(ProviderFailureCode.INVALID_MODEL_PARAMETERS)
        }
        val headers = ResponsesRequestHeaders.byok(resolved, credential)
        return exchange.post(body, headers, events).requireSuccess()
    }
}

private suspend fun checkS308Active(cancellation: ProviderCancellation) {
    currentCoroutineContext().ensureActive()
    if (cancellation.isCancelled()) throw CancellationException("provider call cancelled")
}

/** Same narrow-flag watch as the shared Responses port; cancellation never outlives the caller. */
internal suspend fun <T> whileS308ProviderActive(cancellation: ProviderCancellation, action: suspend () -> T): T = coroutineScope {
    checkS308Active(cancellation)
    val operation = async { action() }
    val watch = launch {
        while (isActive) {
            if (cancellation.isCancelled()) {
                operation.cancel(CancellationException("provider call cancelled"))
                break
            }
            delay(CANCEL_POLL_MILLIS_S308)
        }
    }
    try {
        operation.await()
    } finally {
        watch.cancel()
    }
}

private const val CANCEL_POLL_MILLIS_S308 = 25L
