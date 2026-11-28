package com.deepstudent.features.chat.api

/**
 * One developer-chosen model connection. [credentialReference] names a stored secret and is never
 * the secret itself. [headers] may carry caller-supplied secrets such as an API key, so this value
 * must not be logged, serialized to diagnostics, or reported to crash collection.
 *
 * [model] and [provider] may be blank for discovery; [endpoint] is required.
 */
data class ModelPlaygroundTarget(
    val provider: String,
    val endpoint: String,
    val model: String,
    val protocol: String? = null,
    val authMode: String? = null,
    val credentialReference: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        require(provider.length <= 64) { "invalid playground provider" }
        require(endpoint.isNotBlank() && endpoint.length <= 2048) { "invalid playground endpoint" }
        require(model.length <= 512) { "invalid playground model" }
        require(protocol == null || LABEL.matches(protocol)) { "invalid playground protocol" }
        require(authMode == null || LABEL.matches(authMode)) { "invalid playground auth mode" }
        require(credentialReference == null || CREDENTIAL.matches(credentialReference)) {
            "invalid playground credential reference"
        }
        require(headers.size <= MAX_HEADERS) { "too many playground headers" }
        for ((name, value) in headers) {
            require(HEADER_NAME.matches(name)) { "invalid playground header name" }
            require(value.length <= 4096 && '\r' !in value && '\n' !in value && '\u0000' !in value) {
                "invalid playground header value"
            }
        }
    }

    /** Endpoint, headers and credentials must never reach logs through this value. */
    override fun toString(): String = "ModelPlaygroundTarget(redacted)"

    private companion object {
        const val MAX_HEADERS = 32
        val LABEL = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val CREDENTIAL = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val HEADER_NAME = Regex("[A-Za-z0-9!#\$%&'*+.^_`|~-]{1,128}")
    }
}

/**
 * Stable, non-secret failure from a playground port. Ports must report a code instead of an
 * exception message, status line, URL or response body because those can contain credentials.
 */
class ModelPlaygroundFailure(val code: String) : Exception() {
    init {
        require(CODE.matches(code)) { "invalid playground failure code" }
    }

    override fun toString(): String = "ModelPlaygroundFailure(code=$code)"

    private companion object {
        val CODE = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}

/** Execution boundary for the developer model playground; implementations own transport and credentials. */
interface ModelPlaygroundPorts {
    /** Lists models offered by [target]. The target model may be blank while discovering. */
    suspend fun models(target: ModelPlaygroundTarget): List<String>

    /**
     * Streams one completion for [prompt] in the caller-owned [sessionId], forwarding text deltas to
     * [emit]. Returns on completion, throws [ModelPlaygroundFailure] on a redacted failure, and
     * honours coroutine cancellation.
     */
    suspend fun stream(target: ModelPlaygroundTarget, prompt: String, sessionId: String, emit: suspend (String) -> Unit)
}
