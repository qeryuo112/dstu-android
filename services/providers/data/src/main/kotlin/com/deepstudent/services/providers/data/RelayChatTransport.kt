package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.HttpResponse
import com.deepstudent.platform.network.NetworkHeader
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.platform.network.SseLimits
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderStreamPort
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import kotlin.coroutines.cancellation.CancellationException

/** One composed relay call; the session id is supplied by the wrapping port before streaming. */
internal class RelaySession {
    @Volatile
    var value: String? = null
}

/**
 * Request/transport boundary installed around the shared Chat Completions port for
 * S3.08 relay members. Outgoing requests get the OpenCode Go identity headers on Go
 * roots (sanitized session, task fallback), the configured custom headers merged
 * absent-only case-insensitively, and the relay body dialect applied to the JSON body.
 */
internal class RelayChatTransport(
    private val delegate: NetworkTransport,
    private val resolved: ResolvedProviderConfig,
    private val version: String,
    private val session: RelaySession,
) : NetworkTransport {
    private val openCodeGo: Boolean = OpenCodeGoCatalog.isOpencodeGoConfig(resolved.vendor.id, resolved.providerType, resolved.baseUrl)

    override suspend fun execute(request: NetworkRequest, limits: HttpLimits): HttpResponse = delegate.execute(transform(request), limits)

    override suspend fun streamSse(
        request: NetworkRequest,
        limits: SseLimits,
        onResponse: suspend (ResponseHead) -> Unit,
        onEvent: suspend (SseEvent) -> Unit,
    ): SseEnd = delegate.streamSse(transform(request), limits, onResponse, onEvent)

    /** Owns nothing; the composed transport is closed by its owner. */
    override fun close() = Unit

    private fun transform(request: NetworkRequest): NetworkRequest {
        val headers = request.headers.toMutableList()
        // Frozen order: identity headers first, then configured headers, both absent-only.
        if (openCodeGo) {
            // sanitize(session) ?: fixed task identifier (frozen TASK_SESSION_ID fallback).
            val sessionValue = OpenCodeGoCatalog.sessionHeaderValue(session.value) ?: OpenCodeGoCatalog.TASK_SESSION_ID
            for ((name, value) in OpenCodeGoCatalog.identityHeaders(sessionValue, version)) {
                RelayHeaders.appendIfAbsent(headers, name, value)
            }
        }
        RelayHeaders.mergeConfigured(headers, resolved.headers)
        return NetworkRequest(request.url, request.method, headers, RelayBodyDialect.apply(request.body, resolved))
    }
}

/** Wraps the shared Chat Completions port with S3.08 structured, redacted lifecycle logs. */
internal class S308RelayChatPort(
    private val inner: ProviderStreamPort,
    private val session: RelaySession,
    private val resolved: ResolvedProviderConfig,
    private val logger: Logger,
) : ProviderStreamPort {
    override suspend fun stream(
        request: ProviderAdapterRequest,
        cancellation: ProviderCancellation,
        events: ProviderEventSink,
    ): ProviderStreamResult {
        session.value = request.sessionId
        val trace = S308Trace(logger, resolved.providerType, "providers.s308.relay")
        trace.record(
            S308Trace.STAGE_STARTED,
            fields = JsonValue.Object.Builder()
                .put("dialect", dialectName())
                .put("identityHeaders", openCodeGo)
                .put("configuredHeaders", resolved.headers.size)
                .build(),
        )
        return try {
            val result = whileS308ProviderActive(cancellation) { inner.stream(request, cancellation, events) }
            trace.record(S308Trace.STAGE_COMPLETED, result.stopReason.name)
            result
        } catch (cancelled: CancellationException) {
            trace.record(S308Trace.STAGE_CANCELLED)
            throw cancelled
        } catch (failure: ProviderException) {
            trace.record(S308Trace.STAGE_FAILED, failure.failure.code.name)
            throw failure
        }
    }

    private val openCodeGo: Boolean = OpenCodeGoCatalog.isOpencodeGoConfig(resolved.vendor.id, resolved.providerType, resolved.baseUrl)

    private fun dialectName(): String = when {
        RelayBodyDialect.isOpenrouter(resolved.providerType, resolved.providerScope, resolved.baseUrl) -> "openrouter"
        else -> "relay"
    }
}
