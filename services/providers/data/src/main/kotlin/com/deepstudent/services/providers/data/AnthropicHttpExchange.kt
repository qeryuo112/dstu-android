package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.network.NetworkError
import com.deepstudent.platform.network.NetworkException
import com.deepstudent.platform.network.NetworkHeader
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseLimits
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderHeader
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/**
 * Anthropic HTTP exchange: one POST to the resolved `/v1/messages` URL with the
 * frozen `x-api-key` / `anthropic-version` header set, configured headers
 * appended only when absent, and an explicit terminal stop for the transport.
 * Failures transport fixed provider codes only; bodies, URLs, header values
 * and identifiers never reach diagnostics.
 */
internal class AnthropicHttpExchange(private val network: NetworkTransport) {
    suspend fun post(body: JsonValue.Object, url: String, headers: List<NetworkHeader>, events: ProviderEventSink): AnthropicExchange {
        val decoder = AnthropicStreamDecoder()
        var status: Int? = null
        val limits = SseLimits(stopAfterEvent = { decoder.terminal })
        val request = NetworkRequest(url, "POST", headers, body.literal().toByteArray(Charsets.UTF_8))
        val end = try {
            network.streamSse(request, limits, onResponse = { status = it.statusCode }) { frame ->
                decoder.accept(frame, events)
            }
        } catch (failure: NetworkException) {
            currentCoroutineContext().ensureActive()
            throw anthropicFailure(failure.toProviderCode(status))
        }
        if (end != SseEnd.REJECTED_HTTP_STATUS) {
            return AnthropicExchange.Streamed(decoder.finish(end), decoder.replayThinkingBlocks())
        }
        return AnthropicExchange.Rejected(status ?: 0)
    }

    private fun NetworkException.toProviderCode(status: Int?): ProviderFailureCode = when (error) {
        NetworkError.PROXY_AUTHENTICATION -> ProviderFailureCode.PROXY_AUTHENTICATION

        NetworkError.CONNECTION -> if (status != null &&
            status in 200..299
        ) {
            ProviderFailureCode.STREAM_TRUNCATED
        } else {
            ProviderFailureCode.NETWORK
        }

        NetworkError.RESPONSE_TOO_LARGE,
        NetworkError.SSE_INVALID_CONTENT_TYPE,
        NetworkError.SSE_TOO_LARGE,
        NetworkError.SSE_INVALID_UTF8,
        -> ProviderFailureCode.PROVIDER_ERROR

        else -> ProviderFailureCode.NETWORK
    }
}

internal sealed interface AnthropicExchange {
    class Streamed(val result: ProviderStreamResult, val replayBlocks: List<JsonValue>) : AnthropicExchange
    class Rejected(val status: Int) : AnthropicExchange

    fun requireSuccess(): Pair<ProviderStreamResult, List<JsonValue>> = when (this) {
        is Streamed -> result to replayBlocks

        is Rejected -> throw anthropicFailure(
            when (status) {
                401, 403 -> ProviderFailureCode.AUTHENTICATION
                407 -> ProviderFailureCode.PROXY_AUTHENTICATION
                429 -> ProviderFailureCode.RATE_LIMITED
                else -> ProviderFailureCode.PROVIDER_ERROR
            },
        )
    }
}

internal object AnthropicRequestHeaders {
    /**
     * Frozen header order: secrets first, then beta features, then configured
     * headers fill gaps without overriding the reserved protocol headers.
     */
    fun byok(config: ResolvedProviderConfig, credential: ProviderCredential?, beta: List<String>): List<NetworkHeader> {
        val headers = linkedMapOf(
            "x-api-key" to apiKey(credential),
            "anthropic-version" to ANTHROPIC_VERSION,
            "content-type" to "application/json",
        )
        if (beta.isNotEmpty()) headers["anthropic-beta"] = beta.joinToString(",")
        config.headers.forEach { headers.putIfAbsent(it.name.lowercase(Locale.ROOT), it.value) }
        if (headers.values.any { value -> value.any { it != '\t' && it !in ' '..'~' } }) {
            throw anthropicFailure(ProviderFailureCode.INVALID_HEADERS)
        }
        return headers.map { (name, value) -> NetworkHeader(name, value) }
    }

    private fun apiKey(credential: ProviderCredential?): String {
        val lease = credential ?: throw anthropicFailure(ProviderFailureCode.CREDENTIAL_UNAVAILABLE)
        return lease.use { bytes ->
            val token = bytes.decodeToString()
            if (token.isEmpty() || token.any { it !in '!'..'~' }) throw anthropicFailure(ProviderFailureCode.AUTHENTICATION)
            token
        }
    }

    internal const val ANTHROPIC_VERSION = "2023-06-01"
}

internal fun anthropicFailure(code: ProviderFailureCode) = ProviderException(ProviderFailure(code))
