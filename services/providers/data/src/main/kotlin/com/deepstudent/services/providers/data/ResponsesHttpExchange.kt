package com.deepstudent.services.providers.data

import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.NetworkError
import com.deepstudent.platform.network.NetworkException
import com.deepstudent.platform.network.NetworkHeader
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseLimits
import com.deepstudent.platform.network.SseRejectionBody
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.CodexRequestAuth
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderHeader
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

internal class ResponsesHttpExchange(private val network: NetworkTransport, private val resolved: ResolvedProviderConfig) {
    suspend fun post(body: ResponsesWireBody, headers: List<NetworkHeader>, events: ProviderEventSink): ResponsesExchange {
        val decoder = OpenAiResponsesDecoder(body.deepseekModerated, body.deepseekWebTools)
        var status: Int? = null
        var quotaRejected = false
        var frameFailure: ProviderException? = null
        val limits = SseLimits(
            stopAfterEvent = { decoder.terminal },
            rejectionBody = if (resolved.authMode == AuthMode.OPENAI_CODEX_OAUTH) {
                SseRejectionBody(404, HttpLimits(CODEX_ERROR_BODY_LIMIT)) { quotaRejected = isCodexQuota(it) }
            } else {
                null
            },
        )
        val request = NetworkRequest(resolved.endpoint, "POST", headers, body.json.literal().toByteArray(Charsets.UTF_8))
        val end = try {
            network.streamSse(request, limits, onResponse = { status = it.statusCode }) { frame ->
                val decoded = try {
                    decoder.accept(frame)
                } catch (failure: ProviderException) {
                    frameFailure = failure
                    throw failure
                }
                decoded.forEach { events.emit(it) }
            }
        } catch (failure: NetworkException) {
            currentCoroutineContext().ensureActive()
            throw frameFailure ?: responsesFailure(failure.toProviderCode(status))
        }
        if (end != SseEnd.REJECTED_HTTP_STATUS) return ResponsesExchange.Streamed(decoder.finish(end))
        return ResponsesExchange.Rejected(if (quotaRejected) 429 else status ?: 0)
    }

    private fun NetworkException.toProviderCode(status: Int?): ProviderFailureCode = when (error) {
        NetworkError.PROXY_AUTHENTICATION -> ProviderFailureCode.PROXY_AUTHENTICATION

        NetworkError.CONNECTION -> if (status != null && status in 200..299) {
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

    private fun isCodexQuota(body: ByteArray): Boolean {
        val text = body.decodeToString().lowercase(Locale.ROOT)
        return CODEX_QUOTA_MARKERS.any(text::contains)
    }

    private companion object {
        const val CODEX_ERROR_BODY_LIMIT = 256 * 1024
        val CODEX_QUOTA_MARKERS = listOf("usage_limit_reached", "usage_not_included", "rate_limit_exceeded", "usage limit")
    }
}

internal object ResponsesRequestHeaders {
    fun byok(config: ResolvedProviderConfig, credential: ProviderCredential?): List<NetworkHeader> {
        val headers = linkedMapOf("content-type" to "application/json")
        if (config.authMode == AuthMode.BEARER_API_KEY) headers["authorization"] = bearer(credential)
        return headers.addConfigured(config.headers)
    }

    fun codex(config: ResolvedProviderConfig, auth: CodexRequestAuth, sessionId: String, version: String): List<NetworkHeader> {
        val accountId = auth.accountIdForRequest()
        if (accountId.isBlank()) throw responsesFailure(ProviderFailureCode.AUTHENTICATION)
        val headers = linkedMapOf(
            "authorization" to bearer(auth.credential),
            "chatgpt-account-id" to accountId,
            "originator" to "codex_cli_rs",
            "openai-beta" to "responses=experimental",
            "session-id" to sessionId,
            "session_id" to sessionId,
            "conversation_id" to sessionId,
            "user-agent" to "deep-student/$version",
            "content-type" to "application/json",
            "accept" to "text/event-stream, application/json",
        )
        if (auth.fedramp) headers["x-openai-fedramp"] = "true"
        return headers.addConfigured(config.headers.filterNot { it.name.equals("x-openai-fedramp", ignoreCase = true) })
    }

    private fun LinkedHashMap<String, String>.addConfigured(configured: List<ProviderHeader>): List<NetworkHeader> {
        configured.forEach { putIfAbsent(it.name.lowercase(Locale.ROOT), it.value) }
        if (values.any { value -> value.any { it != '\t' && it !in ' '..'~' } }) {
            throw responsesFailure(ProviderFailureCode.INVALID_HEADERS)
        }
        return map { (name, value) -> NetworkHeader(name, value) }
    }

    private fun bearer(credential: ProviderCredential?): String {
        val lease = credential ?: throw responsesFailure(ProviderFailureCode.CREDENTIAL_UNAVAILABLE)
        return lease.use { bytes ->
            val token = bytes.decodeToString().trim()
            if (token.isEmpty() || token.any { it !in '!'..'~' }) throw responsesFailure(ProviderFailureCode.AUTHENTICATION)
            "Bearer $token"
        }
    }
}

internal sealed interface ResponsesExchange {
    class Streamed(val result: ProviderStreamResult) : ResponsesExchange
    class Rejected(val status: Int) : ResponsesExchange

    fun isUnauthorized(): Boolean = this is Rejected && status == 401

    fun requireSuccess() = when (this) {
        is Streamed -> result

        is Rejected -> throw responsesFailure(
            when (status) {
                401, 403 -> ProviderFailureCode.AUTHENTICATION
                407 -> ProviderFailureCode.PROXY_AUTHENTICATION
                429 -> ProviderFailureCode.RATE_LIMITED
                else -> ProviderFailureCode.PROVIDER_ERROR
            },
        )
    }
}
