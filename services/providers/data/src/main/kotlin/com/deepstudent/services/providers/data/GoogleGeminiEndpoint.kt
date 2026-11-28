package com.deepstudent.services.providers.data

import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import java.net.URI
import java.net.URLEncoder

/**
 * Strict same-origin endpoint handling for the Google dialect. The route-owned
 * endpoint is the only URL source: origin, version root and action are recovered
 * from it, never from message content. The credential query is percent-encoded
 * and never logged.
 */
internal object GoogleGeminiEndpoint {
    private const val ACTION = ":streamGenerateContent"
    private const val QUERY = "alt=sse"
    private val VERSIONS = setOf("v1", "v1beta")
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")

    fun validate(endpoint: String, allowLoopback: Boolean) {
        parse(endpoint, allowLoopback)
    }

    fun route(endpoint: String): GoogleGeminiRoute = parse(endpoint, allowLoopback = true).route()

    /** Appends the percent-encoded credential to the `alt=sse` query. */
    fun withKey(url: String, key: String?): String = when {
        key == null -> url
        else -> "$url&key=${URLEncoder.encode(key, Charsets.UTF_8)}"
    }

    private class Parsed(val origin: String, val rootPath: String, val version: String) {
        fun route() = GoogleGeminiRoute(origin, rootPath, version)
    }

    private fun parse(endpoint: String, allowLoopback: Boolean): Parsed {
        val uri = secureUri(endpoint, allowLoopback)
        val beforeModels = beforeModels(uri.rawPath.orEmpty())
        val versionStart = beforeModels.lastIndexOf('/')
        if (versionStart < 0) throw invalidEndpoint()
        val version = beforeModels.substring(versionStart + 1)
        if (version !in VERSIONS) throw invalidEndpoint()
        val origin = "${uri.scheme}://${uri.host}${if (uri.port < 0) "" else ":${uri.port}"}"
        return Parsed(origin, beforeModels.substring(0, versionStart).trimEnd('/'), version)
    }

    private fun secureUri(endpoint: String, allowLoopback: Boolean): URI {
        if (endpoint.length > 2048 || endpoint.any(Char::isISOControl)) throw invalidEndpoint()
        val uri = runCatching { URI(endpoint) }.getOrElse { throw invalidEndpoint() }
        if (uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery != QUERY) throw invalidEndpoint()
        if (uri.host.isNullOrBlank() || !isAllowedScheme(uri, allowLoopback)) throw invalidEndpoint()
        requireValidPort(uri)
        return uri
    }

    private fun requireValidPort(uri: URI) {
        if (uri.port != -1 && uri.port !in 1..65_535) throw invalidEndpoint()
    }

    private fun isAllowedScheme(uri: URI, allowLoopback: Boolean): Boolean =
        uri.scheme == "https" || (allowLoopback && uri.scheme == "http" && uri.host in LOOPBACK_HOSTS)

    private fun beforeModels(path: String): String {
        val index = path.lastIndexOf("/models/")
        if (index < 0) throw invalidEndpoint()
        val tail = path.substring(index + "/models/".length)
        if (!tail.endsWith(ACTION) || tail.length <= ACTION.length) throw invalidEndpoint()
        return path.substring(0, index)
    }

    private fun invalidEndpoint() = ProviderException(ProviderFailure(ProviderFailureCode.INVALID_ENDPOINT))
}
