package com.deepstudent.services.providers.data

import com.deepstudent.services.providers.api.ProviderUsage

enum class OpenAiOutcomeKind {
    COMPLETED,
    CLEAN_EOF,
    TRUNCATED,
    HTTP_ERROR,
    PROVIDER_ERROR,
}

data class OpenAiToolCallDelta(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val argumentsFragment: String? = null,
    val rawJson: String? = null,
)

sealed interface OpenAiDecodeEvent {
    data class TextDelta(val text: String) : OpenAiDecodeEvent
    data class ReasoningDelta(val text: String) : OpenAiDecodeEvent
    data class ToolCallDelta(val call: OpenAiToolCallDelta) : OpenAiDecodeEvent
    data class UsageReported(val usage: ProviderUsage) : OpenAiDecodeEvent
    data class FinishReason(val value: String) : OpenAiDecodeEvent
    data object Done : OpenAiDecodeEvent
    data class ProviderError(val code: String? = null, val message: String? = null) : OpenAiDecodeEvent
}

data class OpenAiStreamOutcome(
    val kind: OpenAiOutcomeKind,
    val text: String = "",
    val reasoning: String? = null,
    val usage: ProviderUsage? = null,
    val finishReason: String? = null,
    val statusCode: Int? = null,
    val providerCode: String? = null,
    val providerMessage: String? = null,
)
