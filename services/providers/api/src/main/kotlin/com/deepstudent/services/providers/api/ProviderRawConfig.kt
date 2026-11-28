package com.deepstudent.services.providers.api

/** Untrusted configuration text. Size and shape are validated by the decoder. */
class ProviderRawConfig(val json: String) {
    override fun toString(): String = "ProviderRawConfig(redacted)"
}

/** Untrusted vendor fields. Only a successful decoder result becomes VendorConfig. */
data class RawVendorConfig(
    val identity: RawVendorIdentity,
    val baseUrl: String,
    val authMode: String?,
    val protocol: String?,
    val supportsOpenaiResponses: Boolean?,
    val credentialKey: String?,
    val headersJson: String,
)

data class RawVendorIdentity(val id: String, val providerType: String, val name: String)

/** Untrusted profile fields. */
data class RawModelProfile(
    val identity: RawModelIdentity,
    val protocol: String?,
    val adapter: String,
    val generation: RawGenerationParameters,
    val sampling: RawSamplingParameters,
)

data class RawModelIdentity(val id: String, val vendorId: String, val model: String, val label: String, val providerScope: String?)

data class RawReasoningFlags(val isReasoning: Boolean?, val thinkingEnabled: Boolean?)

data class RawGenerationParameters(
    val maxOutputTokens: Long,
    val temperature: String,
    val reasoningEffort: String?,
    val thinkingBudget: String?,
    val includeThoughts: Boolean,
    val reasoningMode: String?,
    val extraBodyJson: String,
    val reasoningFlags: RawReasoningFlags = RawReasoningFlags(null, null),
)

data class RawSamplingParameters(val values: RawSamplingValues, val limits: RawModelLimits, val state: RawModelState)

data class RawSamplingValues(
    val topP: String?,
    val topK: String?,
    val minP: String?,
    val frequencyPenalty: String?,
    val repetitionPenalty: String?,
    val presencePenalty: String?,
    val reasoningSplit: Boolean? = null,
)

data class RawModelLimits(val maxTokensLimit: String?, val contextWindow: String?)

data class RawModelState(
    val enabled: Boolean,
    val status: String,
    val isBuiltin: Boolean,
    val capabilities: RawCapabilities,
    val features: RawFeatures,
)

data class RawCapabilities(
    val text: Boolean,
    val streaming: Boolean,
    val tools: Boolean,
    val images: Boolean,
    val audio: Boolean,
    val video: Boolean,
)

data class RawFeatures(
    val reasoning: Boolean,
    val embeddings: Boolean,
    val imageGeneration: Boolean,
    val reranking: Boolean,
    val favorite: Boolean,
)
