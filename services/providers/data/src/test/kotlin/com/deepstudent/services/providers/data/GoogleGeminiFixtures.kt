package com.deepstudent.services.providers.data

import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ModelParameters
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ModelRuntime
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderProtocolSource
import com.deepstudent.services.providers.api.ProviderRoute
import com.deepstudent.services.providers.api.ReasoningFlags
import com.deepstudent.services.providers.api.ReasoningParameters
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity

/** Shared synthetic Google/Gemini resolution for the S3.07 JVM tests. */
internal object GoogleGeminiFixtures {
    fun resolved(
        model: String,
        reasoning: ReasoningParameters = ReasoningParameters(),
        thinkingEnabled: Boolean = true,
        extraBody: Map<String, String> = emptyMap(),
        isReasoning: Boolean = model.contains("gemini-3") || model.contains("gemini-2.5"),
        baseUrl: String = "https://generativelanguage.example",
    ): ResolvedProviderConfig {
        val version = extraBody["gemini_api_version"]
            ?: baseUrl.substringAfterLast('/').takeIf { it == "v1" || it == "v1beta" }
            ?: "v1"
        val runtime = ModelRuntime(
            capabilities = com.deepstudent.services.providers.api.ModelCapabilities(tools = true, images = true),
            reasoning = reasoning.copy(flags = ReasoningFlags(isReasoning = isReasoning, thinkingEnabled = thinkingEnabled)),
            parameters = ModelParameters(
                maxOutputTokens = 2048,
                temperature = 0.25,
                topP = 0.75,
                topK = 12,
                extraBody = extraBody,
            ),
            maxTokensLimit = 1536,
        )
        val route = ProviderRoute(
            baseUrl = baseUrl,
            endpoint = "$baseUrl/$version/models/$model:streamGenerateContent?alt=sse",
            authMode = AuthMode.X_API_KEY,
            credential = null,
            headers = emptyList(),
            adapterId = "google",
        )
        return ResolvedProviderConfig(
            vendor = VendorConfig(
                VendorIdentity("fixture-vendor", "google"),
                VendorConnection(baseUrl, AuthMode.X_API_KEY, null),
            ),
            profile = ModelProfile("fixture-profile", "fixture-vendor", model, providerScope = "google"),
            runtime = runtime,
            providerType = "google",
            providerScope = "google",
            protocol = ProviderProtocol.GOOGLE_GENERATE_CONTENT,
            protocolSource = ProviderProtocolSource.DEFAULT,
            route = route,
        )
    }
}
