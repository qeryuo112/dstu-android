package com.deepstudent.services.providers.backend

import com.deepstudent.services.providers.api.ModelAdapter
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.OpenAiResponsesRoutes
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderProtocol.ANTHROPIC_MESSAGES
import com.deepstudent.services.providers.api.ProviderProtocol.GOOGLE_GENERATE_CONTENT
import com.deepstudent.services.providers.api.ProviderProtocol.OPENAI_CHAT_COMPLETIONS
import com.deepstudent.services.providers.api.ProviderProtocol.OPENAI_RESPONSES
import com.deepstudent.services.providers.api.ProviderProtocolSource
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderSelection

internal data class ProtocolSelection(val value: ProviderProtocol, val source: ProviderProtocolSource)

internal fun resolveProtocol(record: ProviderProtocolRecord, selection: ProviderSelection): ProviderResult<ProtocolSelection> {
    val protocol = selection.requestedProtocol ?: preferredProtocol(record, selection)
    if (!supportsProtocol(record, selection, protocol)) {
        return providerFailure(ProviderFailureCode.PROTOCOL_NOT_ALLOWED_BY_DEFAULT)
    }
    if (protocol == OPENAI_RESPONSES && isOfficialDeepseek(record, selection) && !deepseekResponsesModel(selection)) {
        return providerFailure(ProviderFailureCode.UNSUPPORTED_MODEL_ROUTE)
    }
    val manual = selection.explicitProtocol != null || protocol !in record.allowedProtocols
    val source = if (manual) ProviderProtocolSource.MANUAL else ProviderProtocolSource.DEFAULT
    return ProviderResult.Success(ProtocolSelection(protocol, source))
}

private fun supportsProtocol(record: ProviderProtocolRecord, selection: ProviderSelection, protocol: ProviderProtocol): Boolean =
    when (protocol) {
        OPENAI_RESPONSES -> supportsResponses(record, selection)
        else -> protocol in record.allowedProtocols || ProviderProtocolPolicy.allowsManual(record.providerType, protocol)
    }

private fun preferredProtocol(record: ProviderProtocolRecord, selection: ProviderSelection): ProviderProtocol {
    val native = nativeProtocol(selection.profile.adapter)
    if (native in record.allowedProtocols) return checkNotNull(native)
    return when (record.providerType) {
        "openai" -> if (supportsResponses(record, selection)) OPENAI_RESPONSES else OPENAI_CHAT_COMPLETIONS
        "deepseek" -> preferredDeepseekProtocol(record, selection)
        else -> defaultProtocol(record, selection)
    }
}

private fun defaultProtocol(record: ProviderProtocolRecord, selection: ProviderSelection): ProviderProtocol =
    if (selection.vendor.supportsOpenaiResponses == true && OPENAI_RESPONSES in record.allowedProtocols) {
        OPENAI_RESPONSES
    } else {
        record.defaultProtocol
    }

private fun preferredDeepseekProtocol(record: ProviderProtocolRecord, selection: ProviderSelection): ProviderProtocol =
    if (supportsResponses(record, selection) && (!isOfficialDeepseek(record, selection) || deepseekResponsesModel(selection))) {
        OPENAI_RESPONSES
    } else {
        OPENAI_CHAT_COMPLETIONS
    }

private fun supportsResponses(record: ProviderProtocolRecord, selection: ProviderSelection): Boolean {
    if (OPENAI_RESPONSES !in record.allowedProtocols) return false
    if (selection.vendor.supportsOpenaiResponses == true) return true
    return when (record.providerType) {
        "openai" -> officialHost(selection, "api.openai.com")
        "deepseek" -> isOfficialDeepseek(record, selection)
        else -> record.supportsOpenaiResponses || selection.explicitProtocol == OPENAI_RESPONSES
    }
}

private fun officialHost(selection: ProviderSelection, hostname: String): Boolean =
    selection.vendor.baseUrl.isBlank() || providerHostname(selection.vendor.baseUrl) == hostname

private fun isOfficialDeepseek(record: ProviderProtocolRecord, selection: ProviderSelection): Boolean =
    record.providerType == "deepseek" && officialHost(selection, "api.deepseek.com")

private fun deepseekResponsesModel(selection: ProviderSelection): Boolean {
    val model = selection.profile.model.trim().lowercase()
    val aliases = setOf("deepseek-chat", "deepseek-reasoner")
    val modelFamilies = listOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro")
    return model in aliases || modelFamilies.any(model::contains)
}

private fun nativeProtocol(adapter: ModelAdapter): ProviderProtocol? = when (adapter) {
    ModelAdapter.ANTHROPIC -> ANTHROPIC_MESSAGES
    ModelAdapter.GOOGLE -> GOOGLE_GENERATE_CONTENT
    else -> null
}

internal fun selectAdapterId(provider: String, profile: ModelProfile, protocol: ProviderProtocol): String = when {
    protocol == ANTHROPIC_MESSAGES -> "anthropic"
    protocol == GOOGLE_GENERATE_CONTENT -> "google"
    provider in setOf("custom", "general", "openrouter", "ollama", "opencode_go") -> "general"
    protocol == OPENAI_CHAT_COMPLETIONS -> ChatCompletionsVendors.requestAdapter(provider, profile) ?: profile.adapter.wireName
    protocol == OPENAI_RESPONSES && OpenAiResponsesRoutes.supports(provider) -> responseAdapter(provider, profile)
    else -> profile.adapter.wireName
}

private fun responseAdapter(provider: String, profile: ModelProfile): String {
    if (provider == "openai_codex" || nativeProtocol(profile.adapter) != null) return ModelAdapter.GENERAL.wireName
    val fallback = when (provider) {
        "deepseek" -> ModelAdapter.DEEPSEEK
        "grok", "xai" -> ModelAdapter.GROK
        else -> ModelAdapter.GENERAL
    }
    return OpenAiAdapterScopes.resolve(provider, profile, fallback).wireName
}
