package com.deepstudent.services.providers.data

import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import java.net.URI

internal object EndpointValidation {
    fun requireChatEndpoint(endpoint: String, allowLoopback: Boolean) {
        requireEndpoint(endpoint, allowLoopback, "/chat/completions")
    }

    fun requireResponsesEndpoint(endpoint: String, allowLoopback: Boolean) {
        requireEndpoint(endpoint, allowLoopback, "/responses")
    }

    private fun requireEndpoint(endpoint: String, allowLoopback: Boolean, suffix: String) {
        val uri = parse(endpoint)
        requireSecure(uri, allowLoopback)
        requireSafeShape(uri)
        if (!uri.path.orEmpty().lowercase().endsWith(suffix)) throw invalid()
    }

    private fun parse(endpoint: String): URI {
        if (endpoint.length > 2048 || endpoint.any(Char::isISOControl)) throw invalid()
        return runCatching { URI(endpoint) }.getOrElse { throw invalid() }
    }

    private fun requireSecure(uri: URI, allowLoopback: Boolean) {
        val loopback = allowLoopback && uri.scheme == "http" && uri.host in LOOPBACK_HOSTS
        if ((uri.scheme != "https" && !loopback) || uri.host.isNullOrBlank()) {
            throw failure(ProviderFailureCode.INSECURE_ENDPOINT)
        }
    }

    private fun requireSafeShape(uri: URI) {
        if (uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery != null) throw invalid()
    }

    private fun failure(code: ProviderFailureCode) = ProviderException(ProviderFailure(code))

    private fun invalid() = failure(ProviderFailureCode.INVALID_ENDPOINT)

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")
}
