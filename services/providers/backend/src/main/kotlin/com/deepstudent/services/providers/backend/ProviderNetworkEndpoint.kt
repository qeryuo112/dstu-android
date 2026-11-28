package com.deepstudent.services.providers.backend

import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderResult
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal data class ProviderNetworkEndpoint(val origin: String, val path: String) {
    val full: String get() = origin + path

    override fun toString(): String = "ProviderNetworkEndpoint(redacted)"
}

internal fun validateProviderEndpoint(value: String, allowLoopback: Boolean): ProviderResult<ProviderNetworkEndpoint> {
    if (value.length > 2048) return endpointFailure(ProviderFailureCode.INVALID_ENDPOINT)
    val uri = parseProviderUri(value) ?: return endpointFailure(ProviderFailureCode.INVALID_ENDPOINT)
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return endpointFailure(ProviderFailureCode.INVALID_ENDPOINT)
    val host = uri.host?.lowercase(Locale.ROOT) ?: return endpointFailure(ProviderFailureCode.INVALID_ENDPOINT)
    if (!uri.hasAllowedComponents()) return endpointFailure(ProviderFailureCode.INVALID_ENDPOINT)
    schemeFailure(scheme, host, allowLoopback)?.let { return endpointFailure(it) }
    val port = if (uri.port < 0) "" else ":${uri.port}"
    return ProviderResult.Success(ProviderNetworkEndpoint("$scheme://$host$port", uri.rawPath.orEmpty()))
}

private fun parseProviderUri(value: String): URI? = try {
    URI(value.trim())
} catch (_: URISyntaxException) {
    null
}

private fun schemeFailure(scheme: String, host: String, allowLoopback: Boolean): ProviderFailureCode? = when (scheme) {
    "https" -> null
    "http" -> if (allowedLoopback(host, allowLoopback)) null else ProviderFailureCode.INSECURE_ENDPOINT
    else -> ProviderFailureCode.INVALID_ENDPOINT
}

private fun allowedLoopback(host: String, allowLoopback: Boolean): Boolean =
    allowLoopback && host in setOf("127.0.0.1", "localhost", "[::1]")

private fun URI.hasAllowedComponents(): Boolean = rawUserInfo == null && rawQuery == null && rawFragment == null &&
    (port == -1 || port in 1..65_535)

private fun endpointFailure(code: ProviderFailureCode) = ProviderResult.Failure(ProviderFailure(code))

internal fun providerHostname(value: String): String? = parseProviderUri(value)?.host?.lowercase(Locale.ROOT)
