package com.deepstudent.services.providers.data

import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import java.net.URI

/**
 * Anthropic endpoint contract
 * (`providers/anthropic.ts` buildRequest at e6b2657fd59014a73f5bbb1d14c2db732cf23e87):
 * the configured base resolves to a same-origin `/v1/messages` URL in three
 * branches — a base that already ends with `/v1/messages` or `/messages` is
 * used verbatim, a base ending with `/v1` gains `/messages`, and any other
 * base gains `/v1/messages`. Credentials, query strings and fragments never
 * reach the wire, and the derived URL must stay on the origin the route layer
 * validated.
 */
internal object AnthropicEndpoint {
    fun messagesUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return when {
            trimmed.endsWith("/v1/messages") || trimmed.endsWith("/messages") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/messages"
            else -> "$trimmed/v1/messages"
        }
    }

    /** Full preflight for the resolved route before any port is constructed. */
    fun requireValid(resolved: ResolvedProviderConfig, allowLoopback: Boolean): String {
        val derived = messagesUrl(resolved.baseUrl)
        val endpoint = parse(resolved.endpoint)
        val url = parse(derived)
        requireSecure(endpoint, allowLoopback)
        requireSecure(url, allowLoopback)
        requireSafeShape(endpoint)
        requireSafeShape(url)
        if (!url.path.orEmpty().endsWith("/messages")) throw failure(ProviderFailureCode.INVALID_ENDPOINT)
        if (origin(endpoint) != origin(url)) throw failure(ProviderFailureCode.INVALID_ENDPOINT)
        return derived
    }

    private fun parse(value: String): URI {
        if (value.length > 2048 || value.any(Char::isISOControl)) throw failure(ProviderFailureCode.INVALID_ENDPOINT)
        return runCatching { URI(value) }.getOrElse { throw failure(ProviderFailureCode.INVALID_ENDPOINT) }
    }

    private fun requireSecure(uri: URI, allowLoopback: Boolean) {
        val loopback = allowLoopback && uri.scheme == "http" && uri.host in LOOPBACK_HOSTS
        if ((uri.scheme != "https" && !loopback) || uri.host.isNullOrBlank()) {
            throw failure(ProviderFailureCode.INSECURE_ENDPOINT)
        }
    }

    private fun requireSafeShape(uri: URI) {
        if (uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery != null) {
            throw failure(ProviderFailureCode.INVALID_ENDPOINT)
        }
    }

    private fun origin(uri: URI): String {
        val defaultPort = when (uri.scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
        val port = uri.port
        return "${uri.scheme}://${uri.host?.lowercase()}${if (port < 0 || port == defaultPort) "" else ":$port"}"
    }

    private fun failure(code: ProviderFailureCode) = ProviderException(ProviderFailure(code))

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")
}
