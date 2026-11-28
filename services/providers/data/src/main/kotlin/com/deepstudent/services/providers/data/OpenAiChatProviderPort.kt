package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.network.NetworkError
import com.deepstudent.platform.network.NetworkException
import com.deepstudent.platform.network.NetworkHeader
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/** Provider-aware Chat Completions adapter. Credential ownership remains with the caller. */
class OpenAiChatProviderFactory(
    private val network: NetworkTransport,
    private val logger: Logger = NoopLogger,
    override val key: ProviderAdapterKey = ProviderAdapterKey("openai", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "general"),
    private val allowLoopback: Boolean = false,
    private val legacyDirectRequest: Boolean = false,
) : ProviderAdapterFactory {
    override suspend fun create(resolved: ResolvedProviderConfig, credential: ProviderCredential?): ProviderStreamPort {
        validateAuth(resolved)
        EndpointValidation.requireChatEndpoint(resolved.endpoint, allowLoopback)
        return OpenAiChatProviderPort(network, resolved, credential, logger, legacyDirectRequest)
    }
}

internal class OpenAiChatProviderPort(
    private val network: NetworkTransport,
    private val resolved: ResolvedProviderConfig,
    private val credential: ProviderCredential?,
    private val logger: Logger = NoopLogger,
    private val legacyDirectRequest: Boolean = false,
) : ProviderStreamPort {
    override suspend fun stream(
        request: ProviderAdapterRequest,
        cancellation: ProviderCancellation,
        events: ProviderEventSink,
    ): ProviderStreamResult {
        checkProtocol(request)
        checkCancellation(cancellation)
        val profile = OpenAiChatRequestPlanner.resolveProfile(request.resolved)
        val state = DecodeState(events)
        runCatching {
            logger.info(
                EVENT_STARTED,
                fields = fields(
                    "protocol" to resolved.protocol.wireName,
                    "provider" to (OpenAiChatProviderProfile.forProvider(resolved.providerType)?.wireProvider ?: "generic"),
                    "profile" to profile.name,
                    "legacy" to legacyDirectRequest.toString(),
                ),
            )
        }
        try {
            val end = network.streamSse(
                openAiRequest(request),
                limits = if (legacyDirectRequest) {
                    SseLimits()
                } else {
                    SseLimits(
                        stopAfterEvent = { event -> event.data.trim() == OpenAiChatDecoder.DONE },
                    )
                },
                onResponse = state::applyResponse,
                onEvent = { event -> state.accept(event.data, cancellation) },
            )
            checkCancellation(cancellation)
            return state.finish(end)
        } catch (failure: CancellationException) {
            runCatching { logger.info(EVENT_CANCELLED, fields = fields("profile" to profile.name)) }
            throw failure
        } catch (failure: ProviderException) {
            logFailure(failure)
            throw failure
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            val code = if ((failure as? NetworkException)?.error == NetworkError.PROXY_AUTHENTICATION) {
                ProviderFailureCode.PROXY_AUTHENTICATION
            } else {
                ProviderFailureCode.NETWORK
            }
            val mapped = state.failure() ?: failure(code)
            logFailure(mapped)
            throw mapped
        }
    }

    private fun logFailure(failure: ProviderException) {
        runCatching { logger.warn(EVENT_FAILED, fields = fields("code" to failure.failure.code.name)) }
    }

    private fun checkProtocol(request: ProviderAdapterRequest) {
        if (request.resolved.protocol != ProviderProtocol.OPENAI_CHAT_COMPLETIONS || resolved.protocol != request.resolved.protocol) {
            throw ProviderException(ProviderFailure(ProviderFailureCode.UNSUPPORTED_PROTOCOL))
        }
        if (request.resolved != resolved) throw failure(ProviderFailureCode.INVALID_CONFIGURATION)
    }

    private fun openAiRequest(request: ProviderAdapterRequest): NetworkRequest {
        val headers = mutableListOf(NetworkHeader("Content-Type", "application/json"))
        when (resolved.authMode) {
            AuthMode.NONE -> Unit
            AuthMode.BEARER_API_KEY -> headers += bearerHeader()
            else -> throw ProviderException(ProviderFailure(ProviderFailureCode.INVALID_AUTH_MODE))
        }
        return NetworkRequest(
            url = resolved.endpoint,
            method = "POST",
            headers = headers,
            body = requestBody(request).literal().toByteArray(Charsets.UTF_8),
        )
    }

    private fun requestBody(request: ProviderAdapterRequest): JsonValue.Object = when {
        legacyDirectRequest -> OpenAiChatRequest.body(resolved.profile.model, request.messages)
        else -> OpenAiChatRequestPlanner.plan(request).body
    }

    private fun bearerHeader(): NetworkHeader {
        val value = credential ?: throw ProviderException(ProviderFailure(ProviderFailureCode.CREDENTIAL_UNAVAILABLE))
        return value.use { bytes ->
            val token = String(bytes, Charsets.UTF_8)
            if (token.any { it !in '!'..'~' }) {
                throw ProviderException(ProviderFailure(ProviderFailureCode.INVALID_AUTH_MODE))
            }
            NetworkHeader("Authorization", "Bearer $token")
        }
    }

    private fun checkCancellation(cancellation: ProviderCancellation) {
        if (cancellation.isCancelled()) throw CancellationException("provider request cancelled")
    }

    private fun fields(vararg entries: Pair<String, String>): JsonValue.Object = JsonValue.Object.Builder().apply {
        entries.forEach { (key, value) -> put(key, value) }
    }.build()

    private inner class DecodeState(private val events: ProviderEventSink) {
        private val decoder = OpenAiChatDecoder()
        private val text = StringBuilder()
        private var usage: ProviderUsage? = null
        private var terminalStopReason: ProviderStopReason? = null
        private var done = false
        private var sawPayload = false
        private var completionAvailable = false
        private var httpStatus: Int? = null
        private var providerFailure: ProviderException? = null

        fun applyResponse(head: com.deepstudent.platform.network.ResponseHead) {
            httpStatus = head.statusCode
        }

        suspend fun accept(data: String, cancellation: ProviderCancellation) {
            checkCancellation(cancellation)
            if (data.isEmpty()) return
            try {
                decoder.decode(data).forEach { apply(it) }
            } catch (failure: ProviderException) {
                providerFailure = failure
                throw failure
            }
            if (decoder.allChoicesFinished()) completionAvailable = true
        }

        fun failure(): ProviderException? = providerFailure

        private suspend fun apply(event: OpenAiDecodeEvent) {
            when (event) {
                is OpenAiDecodeEvent.TextDelta -> {
                    sawPayload = true
                    text.append(event.text)
                    events.emit(ProviderStreamEvent.TextDelta(event.text))
                }

                is OpenAiDecodeEvent.ReasoningDelta -> {
                    if (event.text.isNotEmpty()) sawPayload = true
                    events.emit(ProviderStreamEvent.ReasoningDelta(event.text))
                }

                is OpenAiDecodeEvent.ToolCallDelta -> applyTool(event.call)

                is OpenAiDecodeEvent.UsageReported -> {
                    usage = event.usage
                    events.emit(ProviderStreamEvent.UsageReported(event.usage))
                }

                is OpenAiDecodeEvent.FinishReason -> applyStopReason(stopReason(event.value))

                is OpenAiDecodeEvent.Done -> done = true

                is OpenAiDecodeEvent.ProviderError -> throw failure(ProviderFailureCode.PROVIDER_ERROR)
            }
        }

        private suspend fun applyTool(delta: OpenAiToolCallDelta) {
            sawPayload = true
            if (terminalStopReason == null || terminalStopReason == ProviderStopReason.STOP) {
                terminalStopReason = ProviderStopReason.TOOL_CALL
            }
            events.emit(
                ProviderStreamEvent.ToolCallDelta(delta.index, delta.id, delta.type, delta.name, delta.argumentsFragment, delta.rawJson),
            )
        }

        private fun applyStopReason(reason: ProviderStopReason) {
            if (terminalStopReason != ProviderStopReason.TOOL_CALL || reason != ProviderStopReason.STOP) terminalStopReason = reason
        }

        suspend fun finish(end: SseEnd): ProviderStreamResult {
            if (end == SseEnd.REJECTED_HTTP_STATUS) throw httpFailure(httpStatus)
            providerFailure?.let { throw it }
            if (!isComplete(end)) throw truncated()
            if (!sawPayload && !legacyDirectRequest) throw failure(ProviderFailureCode.PROVIDER_ERROR)
            val terminal = terminalStopReason ?: ProviderStopReason.STOP
            val result = ProviderStreamResult(text.toString(), usage, terminal)
            events.emit(ProviderStreamEvent.Completed(terminal, result.usage))
            runCatching { logger.info(EVENT_COMPLETED, fields = fields("stopReason" to terminal.name)) }
            return result
        }

        private fun isComplete(end: SseEnd): Boolean = end == SseEnd.COMPLETE && (done || (!legacyDirectRequest && completionAvailable))
    }

    private fun stopReason(reason: String): ProviderStopReason = when (reason.lowercase()) {
        "length" -> ProviderStopReason.LENGTH
        "tool_calls", "function_call" -> ProviderStopReason.TOOL_CALL
        "content_filter" -> ProviderStopReason.CONTENT_FILTER
        else -> ProviderStopReason.STOP
    }

    private fun httpFailure(status: Int?): ProviderException = when {
        status == 401 || status == 403 -> failure(ProviderFailureCode.AUTHENTICATION)
        status == 407 -> failure(ProviderFailureCode.PROXY_AUTHENTICATION)
        status == 429 -> failure(ProviderFailureCode.RATE_LIMITED)
        else -> failure(ProviderFailureCode.PROVIDER_ERROR)
    }

    private fun truncated() = ProviderException(ProviderFailure(ProviderFailureCode.STREAM_TRUNCATED))

    private fun failure(code: ProviderFailureCode) = ProviderException(ProviderFailure(code))

    private companion object {
        const val EVENT_STARTED = "providers.openai_chat.started"
        const val EVENT_COMPLETED = "providers.openai_chat.completed"
        const val EVENT_CANCELLED = "providers.openai_chat.cancelled"
        const val EVENT_FAILED = "providers.openai_chat.failed"
    }
}

private fun validateAuth(resolved: ResolvedProviderConfig) {
    if (resolved.authMode !in setOf(AuthMode.NONE, AuthMode.BEARER_API_KEY)) {
        throw ProviderException(ProviderFailure(ProviderFailureCode.INVALID_AUTH_MODE))
    }
}
