package com.deepstudent.services.providers.api

import java.util.Collections
import java.util.Locale

/** The four wire dialects the frozen desktop can select. */
enum class ProviderProtocol(val wireName: String) {
    OPENAI_CHAT_COMPLETIONS("openai_chat_completions"),
    OPENAI_RESPONSES("openai_responses"),
    ANTHROPIC_MESSAGES("anthropic_messages"),
    GOOGLE_GENERATE_CONTENT("google_generate_content"),
    ;

    companion object {
        fun fromWire(value: String): ProviderProtocol? = entries.firstOrNull { it.wireName == value.trim().lowercase(Locale.ROOT) }
    }
}

/**
 * Explicit header choice. Bearer and x-api-key are not inferred from provider IDs.
 */
enum class AuthMode(val wireName: String) {
    NONE("none"),
    BEARER_API_KEY("bearer_api_key"),
    X_API_KEY("x_api_key"),
    OPENAI_CODEX_OAUTH("openai_codex_oauth"),
    ;

    companion object {
        fun fromWire(value: String?): AuthMode? {
            val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

/** Header names are lowercase; values are never retained in diagnostics. */
data class ProviderHeader(val name: String, val value: String) {
    init {
        ProviderHeaders.validate(listOf(this))
    }

    override fun toString(): String = "ProviderHeader(name=$name, value=redacted)"
}

data class ModelParameters(
    val maxOutputTokens: Int = 8192,
    val temperature: Double = 0.7,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val frequencyPenalty: Double? = null,
    /** Parsed JSON values; a JSON string keeps its exact text, including quote characters. */
    val typedExtraBody: Map<String, String> = emptyMap(),
    /** Legacy flattened map used by supplier routes and older fixtures. */
    val extraBody: Map<String, String> = emptyMap(),
) {
    init {
        require(maxOutputTokens in 1..1_048_576) { "invalid max output tokens" }
        require(temperature.isFinite() && temperature in 0.0..2.0) { "invalid temperature" }
        require(topP == null || (topP.isFinite() && topP in 0.0..1.0)) { "invalid top_p" }
        require(topK == null || topK in 0..1_000_000) { "invalid top_k" }
        require(minP == null || (minP.isFinite() && minP in 0.0..1.0)) { "invalid min_p" }
        require(frequencyPenalty == null || frequencyPenalty.isFinite()) { "invalid frequency penalty" }
        requireExtraBody(typedExtraBody)
        requireExtraBody(extraBody)
    }

    private fun requireExtraBody(values: Map<String, String>) {
        require(values.keys.all { EXTRA_BODY_KEY.matches(it) }) { "invalid extra body key" }
        require(values.values.all { it.length <= 4096 }) { "invalid extra body value" }
    }

    private companion object {
        val EXTRA_BODY_KEY = Regex("[A-Za-z0-9_.-]{1,64}")
    }
}

/** Optional model-family flags kept together to bound public constructor size. */
data class ReasoningFlags(val isReasoning: Boolean? = null, val thinkingEnabled: Boolean? = null)

data class ReasoningParameters(
    val reasoningEffort: String? = null,
    val thinkingBudget: Int? = null,
    val includeThoughts: Boolean = false,
    val reasoningMode: String? = null,
    val repetitionPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val reasoningSplit: Boolean? = null,
    val flags: ReasoningFlags = ReasoningFlags(),
) {
    init {
        require(reasoningEffort == null || reasoningEffort.trim().isNotEmpty()) { "invalid reasoning effort" }
        require(thinkingBudget == null || thinkingBudget >= -1) { "invalid thinking budget" }
        require(reasoningMode == null || reasoningMode.trim().isNotEmpty()) { "invalid reasoning mode" }
        require(repetitionPenalty == null || repetitionPenalty.isFinite()) { "invalid repetition penalty" }
        require(presencePenalty == null || presencePenalty.isFinite()) { "invalid presence penalty" }
    }
}

data class ModelCapabilities(
    val text: Boolean = true,
    val streaming: Boolean = true,
    val tools: Boolean = false,
    val images: Boolean = false,
    val audio: Boolean = false,
    val video: Boolean = false,
) {
    init {
        require(text || images || audio || video) { "model has no usable capability" }
    }
}

data class ModelFeatures(
    val reasoning: Boolean = false,
    val embeddings: Boolean = false,
    val imageGeneration: Boolean = false,
    val reranking: Boolean = false,
    val favorite: Boolean = false,
)

enum class ModelAdapter(val wireName: String) {
    GENERAL("general"),
    ANTHROPIC("anthropic"),
    GOOGLE("google"),
    DEEPSEEK("deepseek"),
    QWEN("qwen"),
    ZHIPU("zhipu"),
    DOUBAO("doubao"),
    ERNIE("ernie"),
    GROK("grok"),
    MISTRAL("mistral"),
    MOONSHOT("moonshot"),
    MINIMAX("minimax"),
    MIMO("mimo"),
    ;

    companion object {
        fun fromWire(value: String?): ModelAdapter? {
            val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

enum class ProviderProtocolSource { DEFAULT, MANUAL }

enum class ProviderFailureCode(val fixedMessage: String) {
    INVALID_CONFIGURATION("provider configuration is invalid"),
    UNKNOWN_PROVIDER("provider is not registered"),
    UNSUPPORTED_PROTOCOL("provider protocol is not supported"),
    PROTOCOL_NOT_ALLOWED_BY_DEFAULT("provider protocol is not allowed without explicit selection"),
    INVALID_ENDPOINT("provider endpoint is invalid"),
    INSECURE_ENDPOINT("provider endpoint requires HTTPS"),
    INVALID_HEADERS("provider headers are invalid"),
    MISSING_CREDENTIAL_REFERENCE("credential reference is required"),
    INVALID_AUTH_MODE("authentication mode is invalid"),
    CREDENTIAL_UNAVAILABLE("credential is unavailable"),
    INVALID_MODEL("model is invalid"),
    UNSUPPORTED_MODEL_ROUTE("model is not supported by this route"),
    INVALID_MODEL_PARAMETERS("model parameters are invalid"),
    DISABLED("provider configuration is disabled"),
    PROTOCOL_ADAPTER_UNAVAILABLE("protocol adapter is unavailable"),
    NETWORK("provider network operation failed"),
    AUTHENTICATION("provider authentication failed"),
    REAUTHENTICATION_REQUIRED("provider account requires a new sign in"),
    PROXY_AUTHENTICATION("network proxy requires authentication"),
    RATE_LIMITED("provider rate limit exceeded"),
    PROVIDER_ERROR("provider rejected the request"),
    STREAM_TRUNCATED("provider stream was truncated"),
    CANCELLED("provider operation was cancelled"),
    INTERNAL("provider operation failed"),
}

data class ProviderFailure(val code: ProviderFailureCode) {
    val message: String get() = code.fixedMessage
}

sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T) : ProviderResult<T>
    data class Failure(val failure: ProviderFailure) : ProviderResult<Nothing>
    data object Cancelled : ProviderResult<Nothing>
}

inline fun <T, R> ProviderResult<T>.map(transform: (T) -> R): ProviderResult<R> = when (this) {
    is ProviderResult.Success -> ProviderResult.Success(transform(value))
    is ProviderResult.Failure -> this
    ProviderResult.Cancelled -> ProviderResult.Cancelled
}

inline fun <T, R> ProviderResult<T>.flatMap(transform: (T) -> ProviderResult<R>): ProviderResult<R> = when (this) {
    is ProviderResult.Success -> transform(value)
    is ProviderResult.Failure -> this
    ProviderResult.Cancelled -> ProviderResult.Cancelled
}

fun <T> ProviderResult<T>.failureOrNull(): ProviderFailure? = (this as? ProviderResult.Failure)?.failure

enum class ProviderStopReason { STOP, LENGTH, TOOL_CALL, CONTENT_FILTER, SAFETY, ERROR }

/**
 * Incremental provider events. Text and reasoning deltas keep their wire
 * fragments; tool deltas are intentionally raw so callers can merge argument
 * fragments by index without losing provider-specific fields.
 */
sealed interface ProviderStreamEvent {
    data class TextDelta(val text: String) : ProviderStreamEvent {
        init {
            require(text.isNotEmpty()) { "empty text delta" }
        }

        override fun toString(): String = "ProviderStreamEvent.TextDelta(redacted)"
    }

    /** An explicit empty reasoning fragment is significant for fallback precedence. */
    data class ReasoningDelta(val text: String) : ProviderStreamEvent {
        override fun toString(): String = "ProviderStreamEvent.ReasoningDelta(redacted)"
    }

    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val type: String? = null,
        val name: String? = null,
        val argumentsFragment: String? = null,
        val rawJson: String? = null,
        /** A terminal Responses tool snapshot replaces accumulated arguments instead of appending to them. */
        val argumentsComplete: Boolean = false,
    ) : ProviderStreamEvent {
        init {
            require(index >= 0) { "invalid tool call index" }
            require(!id.isNullOrEmpty() || !name.isNullOrEmpty() || !argumentsFragment.isNullOrEmpty() || rawJson != null) {
                "empty tool call delta"
            }
        }

        override fun toString(): String = "ProviderStreamEvent.ToolCallDelta(index=$index, details=redacted)"
    }

    /** Provider metadata may contain sensitive or encrypted history; never put it in diagnostic fields. */
    data class ResponsesMetadata(
        val kind: ResponsesMetadataKind,
        val rawJson: String,
        val index: Int? = null,
        val stateScope: String? = null,
    ) : ProviderStreamEvent {
        init {
            require(rawJson.isNotBlank() && (index == null || index >= 0)) { "invalid Responses metadata" }
        }

        override fun toString(): String = "ProviderStreamEvent.ResponsesMetadata(kind=$kind, index=$index, details=redacted)"
    }

    data class UsageReported(val usage: ProviderUsage) : ProviderStreamEvent {
        init {
            require(usage.known) { "usage has no known fields" }
        }
    }

    data class Completed(val stopReason: ProviderStopReason, val usage: ProviderUsage? = null) : ProviderStreamEvent {
        init {
            require(stopReason != ProviderStopReason.ERROR) { "error is not a successful completion" }
        }
    }
}

data class ProviderStreamResult(
    val text: String,
    val usage: ProviderUsage? = null,
    val stopReason: ProviderStopReason,
    /**
     * Ordered protocol-native blocks the provider issued for this successful
     * turn, keyed to the resolved target scope. Failures never produce it.
     */
    val nativeHistory: ProviderNativeHistory? = null,
) {
    init {
        require(stopReason != ProviderStopReason.ERROR) { "error is not a successful result" }
    }

    override fun toString(): String = "ProviderStreamResult(text=redacted, stopReason=$stopReason)"
}

/**
 * Protocol adapter boundary. The callback receives every text/usage event as it
 * arrives, and the adapter must emit exactly one terminal completion.
 */
interface ProviderStreamPort {
    suspend fun stream(
        request: ProviderAdapterRequest,
        cancellation: ProviderCancellation,
        events: ProviderEventSink,
    ): ProviderStreamResult
}

interface ProviderAdapterFactory {
    val key: ProviderAdapterKey
    suspend fun create(resolved: ResolvedProviderConfig, credential: ProviderCredential?): ProviderStreamPort
}

data class ProviderAdapterKey(val providerType: String, val protocol: ProviderProtocol, val adapterId: String) {
    init {
        require(providerType.isNotBlank() && adapterId.isNotBlank())
    }
}

interface ProviderAdapterRegistry {
    fun register(factory: ProviderAdapterFactory)
    fun resolve(key: ProviderAdapterKey): ProviderAdapterFactory?
}

/** No lookup is adapter construction; this happens only after preflight. */
fun interface ProviderAdapterLocator {
    fun find(resolved: ResolvedProviderConfig): ProviderAdapterFactory?
}

/** Secret access is a separate bridge used only after config preflight succeeds. */
fun interface ProviderCredentialStore {
    suspend fun read(reference: CredentialReference): ProviderResult<ProviderCredential>
}

/** Owns mutable text bytes; callers must use [use] and must not retain them. */
class ProviderCredential private constructor(private var bytes: ByteArray) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun <T> use(block: (ByteArray) -> T): T {
        check(!closed) { "credential is closed" }
        return block(bytes)
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            bytes.fill(0)
            closed = true
        }
    }

    override fun toString(): String = "ProviderCredential(value=redacted, closed=$closed)"

    companion object {
        fun fromUtf8(value: CharArray): ProviderCredential = fromBytes(value.concatToString().toByteArray(Charsets.UTF_8))

        fun fromBytes(value: ByteArray): ProviderCredential {
            require(value.isNotEmpty() && value.size <= MAX_BYTES) { "invalid credential" }
            return ProviderCredential(value.copyOf())
        }

        fun takeOwnership(value: ByteArray): ProviderCredential {
            require(value.isNotEmpty() && value.size <= MAX_BYTES) { "invalid credential" }
            return ProviderCredential(value)
        }

        private const val MAX_BYTES = 1024 * 1024
    }
}

object ProviderHeaders {
    private val NAME = Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+")
    private val VALUE = Regex("[\\t\\x20-\\x7E]*")

    fun validate(headers: List<ProviderHeader>) {
        val normalized = headers.map { it.name.lowercase(Locale.ROOT) }
        require(normalized.distinct().size == normalized.size) { "duplicate provider header" }
        headers.forEach {
            require(NAME.matches(it.name)) { "invalid provider header name" }
            require(VALUE.matches(it.value)) { "invalid provider header value" }
        }
    }
}
