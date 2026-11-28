package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ModelRuntime
import com.deepstudent.services.providers.api.ProviderHeader
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderProtocolSource
import com.deepstudent.services.providers.api.ProviderRoute
import com.deepstudent.services.providers.api.ReasoningFlags
import com.deepstudent.services.providers.api.ReasoningParameters
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity
import com.deepstudent.services.providers.api.VendorProtocolDefaults

/** Minimal resolved-config builder for S3.08 boundary tests. */
internal fun s308Resolved(
    provider: String,
    model: String = "test-model",
    baseUrl: String = "https://provider.example/v1",
    endpoint: String = baseUrl.trimEnd('/') + "/chat/completions",
    protocol: ProviderProtocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
    authMode: AuthMode = AuthMode.BEARER_API_KEY,
    headers: List<ProviderHeader> = emptyList(),
    reasoning: ReasoningParameters = ReasoningParameters(),
    reasoningFeature: Boolean = false,
    vendorId: String = "fixture-vendor",
): ResolvedProviderConfig {
    val vendor = VendorConfig(
        VendorIdentity(vendorId, provider, provider),
        VendorConnection(baseUrl, authMode),
        VendorProtocolDefaults(supportsOpenaiResponses = protocol == ProviderProtocol.OPENAI_RESPONSES),
        headers,
    )
    return ResolvedProviderConfig(
        vendor = vendor,
        profile = ModelProfile("fixture-profile", vendor.id, model),
        runtime = ModelRuntime(
            reasoning = reasoning,
            features = com.deepstudent.services.providers.api.ModelFeatures(reasoning = reasoningFeature),
        ),
        providerType = provider,
        providerScope = provider,
        protocol = protocol,
        protocolSource = ProviderProtocolSource.MANUAL,
        route = ProviderRoute(
            baseUrl = baseUrl,
            endpoint = endpoint,
            authMode = authMode,
            credential = null,
            headers = headers,
            adapterId = "general",
        ),
    )
}

internal fun s308Reasoning(effort: String? = null, budget: Int? = null, thinking: Boolean = true, isReasoning: Boolean = true) =
    ReasoningParameters(
        reasoningEffort = effort,
        thinkingBudget = budget,
        flags = ReasoningFlags(isReasoning = isReasoning, thinkingEnabled = thinking),
    )

internal fun JsonValue.Object.s308Text(key: String): String? = (this[key] as? JsonValue.Text)?.value
internal fun JsonValue.Object.s308Obj(key: String): JsonValue.Object? = this[key] as? JsonValue.Object
