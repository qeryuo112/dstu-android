package com.deepstudent.services.providers.api

import java.util.Locale

/** One provider request message, including tool-call and tool-result history. */
data class ProviderMessage(
    val role: ProviderRole,
    val text: String,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null,
    val reasoningContent: String? = null,
    val responses: ProviderResponsesHistory? = null,
    /**
     * Protocol-native parts for this message: preprocessed image data URLs and
     * same-target private replay state. Adapters that understand the protocol
     * read it from the ordinary message list; other adapters ignore it.
     */
    val nativeContent: ProviderNativeMessage? = null,
) {
    override fun toString(): String = "ProviderMessage(role=$role, contents=redacted)"
}

/**
 * Protocol-native message content that must not ride the visible text: image
 * inputs already inlined as `data:` URLs plus the private replay state the
 * provider issued for this message. Both are bounded so a provider payload
 * cannot grow without limit.
 */
data class ProviderNativeMessage(val imageDataUrls: List<String> = emptyList(), val state: ProviderNativeHistory? = null) {
    init {
        require(imageDataUrls.size <= MAX_IMAGE_DATA_URLS) { "too many native image data URLs" }
        require(imageDataUrls.all { it.isNotBlank() && it.length <= MAX_IMAGE_DATA_URL_CHARS }) {
            "invalid native image data URL"
        }
    }

    override fun toString(): String =
        "ProviderNativeMessage(images=${imageDataUrls.size}, state=${if (state != null) "present" else "none"})"

    companion object {
        /** A single user turn never carries more than this many preprocessed images. */
        const val MAX_IMAGE_DATA_URLS = 16

        /** Inline payload bound per image; the transport event budget is smaller. */
        const val MAX_IMAGE_DATA_URL_CHARS = 4 * 1024 * 1024
    }
}

/**
 * Ordered private replay state for one assistant turn, bound to the frozen
 * [stateScope] target tuple (provider, endpoint fingerprint, model, protocol).
 * Signatures and encrypted blocks are replayed only on an exact scope match.
 */
data class ProviderNativeHistory(val protocol: ProviderProtocol, val stateScope: String, val payloadJson: String) {
    init {
        require(stateScope.isNotBlank() && stateScope.length <= MAX_STATE_SCOPE_CHARS) { "invalid native state scope" }
        require(payloadJson.isNotBlank() && payloadJson.length <= MAX_PAYLOAD_CHARS) { "invalid native payload" }
    }

    override fun toString(): String = "ProviderNativeHistory(protocol=${protocol.wireName}, details=redacted)"

    companion object {
        /** The frozen scope tuple is short; this only rejects runaway input. */
        const val MAX_STATE_SCOPE_CHARS = 4096

        /** Serialized ordered blocks; aligned with the maximum SSE event budget. */
        const val MAX_PAYLOAD_CHARS = 4 * 1024 * 1024
    }
}

/** Lossless Responses parts and server-issued history items from a prior turn. */
data class ProviderResponsesHistory(
    val contentPartsJson: String? = null,
    val reasoningItemJson: String? = null,
    val webSearchItemsJson: String? = null,
    val reasoningItemsJson: String? = null,
    val stateScope: String? = null,
) {
    override fun toString(): String = "ProviderResponsesHistory(redacted)"
}

enum class ResponsesMetadataKind { REASONING_ITEM, WEB_SEARCH, ANNOTATIONS }

/** Fixed origin for Codex account credentials; BYOK routes remain separately configurable. */
object OpenAiResponsesRoutes {
    const val CODEX_BASE = "https://chatgpt.com/backend-api/codex"
    const val CODEX_ENDPOINT = "$CODEX_BASE/responses"

    fun supports(providerType: String): Boolean = providerType in providers

    private val providers = setOf("openai", "openai_codex", "deepseek", "grok", "xai")
}

/** Summary of a resolved route, suitable for diagnostics and logs. */
data class ProviderRouteSummary(
    val providerType: String,
    val modelId: String,
    val protocol: ProviderProtocol,
    val source: ProviderProtocolSource,
    val authMode: AuthMode,
    val adapterId: String,
) {
    override fun toString(): String = "ProviderRouteSummary(provider=$providerType, model=$modelId, protocol=${protocol.wireName}, " +
        "auth=${authMode.wireName}, adapter=$adapterId)"
}

/** Typed credential reference, never a secret value. */
data class CredentialReference(val key: String) {
    init {
        require(KEY.matches(key)) { "invalid credential reference" }
    }

    override fun toString(): String = "CredentialReference(redacted)"

    private companion object {
        val KEY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

/** Narrow cancellation check used by the S3.03 adapter boundary. */
fun interface ProviderCancellation {
    fun isCancelled(): Boolean
}

/** Incremental stream callback. */
fun interface ProviderEventSink {
    suspend fun emit(event: ProviderStreamEvent)
}

/** Coarse usage result from the provider adapter. */
data class ProviderUsage(
    val inputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val cacheWriteInputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val totalTokens: Long? = null,
) {
    init {
        require(
            listOf(inputTokens, cachedInputTokens, cacheWriteInputTokens, outputTokens, reasoningTokens, totalTokens)
                .all { it == null || it >= 0 },
        )
        if (totalTokens != null && inputTokens != null && outputTokens != null && totalTokens != inputTokens + outputTokens) {
            throw IllegalArgumentException("inconsistent total tokens")
        }
    }

    val known: Boolean
        get() = inputTokens != null || cachedInputTokens != null || cacheWriteInputTokens != null ||
            outputTokens != null || reasoningTokens != null || totalTokens != null
}

/** Adapter failures are transported as fixed provider errors. */
class ProviderException(val failure: ProviderFailure) : Exception(failure.message)
