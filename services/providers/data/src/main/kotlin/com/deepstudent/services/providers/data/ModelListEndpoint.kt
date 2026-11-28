package com.deepstudent.services.providers.data

import java.net.URI
import java.util.Locale

/**
 * Model-list endpoint derivation for the three vendor dialects, mirroring the frozen
 * `vendor_models.ts` (`vendor_models_endpoint`, `normalizeOllamaRootBaseUrl`).
 */
object ModelListEndpoint {
    enum class Kind { GEMINI, ANTHROPIC, OPENAI }

    /** providers/openai-chat `normalize_ollama_root_base_url`: root :11434 becomes /v1. */
    fun normalizeOllamaRootBaseUrl(baseUrl: String): String {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return baseUrl
        if (uri.port == 11_434 && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") && !baseUrl.contains('?')) {
            return render(uri, "/v1")
        }
        return baseUrl
    }

    /**
     * Strips known protocol suffixes, then appends the dialect models path; query and
     * fragment are dropped like the desktop URL rebuild.
     */
    fun derive(baseUrl: String, providerType: String, apiProtocol: String?): Pair<String, Kind> {
        validateBaseShape(baseUrl)
        val uri = runCatching { URI(normalizeOllamaRootBaseUrl(baseUrl).trim()) }.getOrNull()
            ?: throw ModelListException(ModelListFailureCode.INVALID_BASE_URL)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty()) {
            throw ModelListException(ModelListFailureCode.UNSUPPORTED_BASE_URL_SCHEME)
        }
        val kind = kindOf(providerType.trim().lowercase(Locale.ROOT), (apiProtocol ?: "").trim().lowercase(Locale.ROOT))
        return render(uri, modelsPath((uri.rawPath ?: "").trimEnd('/'), kind)) to kind
    }

    private fun validateBaseShape(baseUrl: String) {
        val base = baseUrl.trim()
        if (base.length > 2048 || base.any(Char::isISOControl)) throw ModelListException(ModelListFailureCode.INVALID_BASE_URL)
        val uri = runCatching { URI(base) }.getOrNull() ?: throw ModelListException(ModelListFailureCode.INVALID_BASE_URL)
        if (uri.rawUserInfo != null || (uri.port != -1 && uri.port !in 1..65_535)) {
            throw ModelListException(ModelListFailureCode.INVALID_BASE_URL)
        }
    }

    /** Frozen dialect selection: explicit OpenAI protocols win, then provider/protocol dialects. */
    private fun kindOf(provider: String, protocol: String): Kind = when {
        isOpenaiProtocol(protocol) -> Kind.OPENAI
        isGeminiDialect(provider, protocol) -> Kind.GEMINI
        isAnthropicDialect(provider, protocol) -> Kind.ANTHROPIC
        else -> Kind.OPENAI
    }

    private fun isOpenaiProtocol(protocol: String): Boolean = protocol == "openai_chat_completions" || protocol == "openai_responses"

    private fun isGeminiDialect(provider: String, protocol: String): Boolean =
        protocol == "google_generate_content" || provider == "gemini" || provider == "google"

    private fun isAnthropicDialect(provider: String, protocol: String): Boolean =
        protocol == "anthropic_messages" || provider == "anthropic" || provider == "claude"

    private fun modelsPath(basePath: String, kind: Kind): String = when (kind) {
        Kind.GEMINI -> geminiPath(basePath)
        Kind.ANTHROPIC -> anthropicPath(basePath)
        Kind.OPENAI -> openaiPath(basePath)
    }

    private fun geminiPath(basePath: String): String {
        val stripped = stripSuffix(basePath, arrayOf("/v1beta/models", "/v1/models", "/models"))
        val versioned = when {
            stripped.isEmpty() -> "/v1beta"
            !stripped.endsWith("/v1beta") && !stripped.endsWith("/v1") -> "$stripped/v1beta"
            else -> stripped
        }
        return "$versioned/models"
    }

    private fun anthropicPath(basePath: String): String {
        val stripped = stripSuffix(basePath, arrayOf("/v1/messages", "/v1/models", "/messages", "/models"))
        val versioned = if (stripped.endsWith("/v1")) stripped else "$stripped/v1"
        return "$versioned/models"
    }

    private fun openaiPath(basePath: String): String =
        stripSuffix(basePath, arrayOf("/chat/completions", "/responses", "/models")) + "/models"

    private fun stripSuffix(path: String, suffixes: Array<String>): String {
        for (suffix in suffixes) {
            if (path.lowercase(Locale.ROOT).endsWith(suffix)) return path.dropLast(suffix.length)
        }
        return path
    }

    /** Serializes scheme/host/port plus the given path; default ports are omitted like URL.href. */
    private fun render(uri: URI, path: String): String = buildString {
        append(uri.scheme?.lowercase(Locale.ROOT)).append("://")
        append(uri.host)
        val port = uri.port
        val default = (uri.scheme == "https" && port == 443) || (uri.scheme == "http" && port == 80)
        if (port >= 0 && !default) append(':').append(port)
        append(path)
    }

    /** The list boundary keeps the shared HTTPS/loopback rules and rejects unsafe shapes. */
    fun validate(url: String, allowLoopback: Boolean) {
        val uri = runCatching { URI(url) }.getOrNull() ?: throw ModelListException(ModelListFailureCode.INVALID_BASE_URL)
        val loopback = allowLoopback && uri.scheme == "http" && uri.host in LOOPBACK_HOSTS
        if ((uri.scheme != "https" && !loopback) || uri.host.isNullOrBlank()) {
            throw ModelListException(ModelListFailureCode.UNSUPPORTED_BASE_URL_SCHEME)
        }
        if (uri.rawUserInfo != null || uri.rawFragment != null) {
            throw ModelListException(ModelListFailureCode.INVALID_BASE_URL)
        }
    }

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")
}

/** Fixed model-list failures; neither the response body nor request secrets enter the diagnostic. */
class ModelListException(val code: ModelListFailureCode, val status: Int? = null) :
    Exception(status?.let { "${code.fixedMessage}: ${RelayDiagnostics.httpStatusDisplay(it)}" } ?: code.fixedMessage)

enum class ModelListFailureCode(val fixedMessage: String) {
    INVALID_BASE_URL("vendor base URL is invalid"),
    UNSUPPORTED_BASE_URL_SCHEME("vendor base URL must be a valid HTTP(S) address"),
    INVALID_HEADER_NAME("vendor header name is invalid"),
    INVALID_HEADER_VALUE("vendor header contains invalid characters"),
    INVALID_API_KEY_CHARACTERS("vendor API key contains invalid characters"),
    MISSING_API_KEY("vendor has no API key configured"),
    FETCH_FAILED("vendor model fetch failed"),
    TIMEOUT("vendor model fetch timed out"),
    HTTP_STATUS("vendor model fetch failed"),
    PARSE_FAILED("vendor model response could not be parsed"),
    MISSING_MODELS_CONTAINER("vendor model response is missing the models container"),
    CANCELLED("vendor model fetch was cancelled"),
}
