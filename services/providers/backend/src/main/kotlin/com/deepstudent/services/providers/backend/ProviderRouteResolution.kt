package com.deepstudent.services.providers.backend

import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.OpenAiResponsesRoutes
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderHeaders
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderProtocol.ANTHROPIC_MESSAGES
import com.deepstudent.services.providers.api.ProviderProtocol.GOOGLE_GENERATE_CONTENT
import com.deepstudent.services.providers.api.ProviderProtocol.OPENAI_CHAT_COMPLETIONS
import com.deepstudent.services.providers.api.ProviderProtocol.OPENAI_RESPONSES
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRoute
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.flatMap
import com.deepstudent.services.providers.api.map
import java.net.URI

internal fun resolveRoute(
    selection: ProviderSelection,
    provider: String,
    protocol: ProviderProtocol,
    allowLoopback: Boolean,
): ProviderResult<ProviderRoute> {
    val vendor = selection.vendor
    if (!validateHeaders(vendor, provider)) return providerFailure(ProviderFailureCode.INVALID_HEADERS)
    return resolveAuth(vendor, provider, protocol).flatMap { auth ->
        // Account bearer tokens may only be sent to the desktop contract's Codex origin/path.
        val url = if (auth ==
            AuthMode.OPENAI_CODEX_OAUTH
        ) {
            OpenAiResponsesRoutes.CODEX_BASE
        } else {
            vendor.baseUrl.ifBlank { builtinBase(provider) }
        }
        validateProviderEndpoint(url, allowLoopback).flatMap { parsed ->
            val base = providerBase(parsed, provider, protocol)
            protocolEndpoint(selection, base, protocol).map { endpoint ->
                makeRoute(selection, provider, protocol, base, endpoint, auth)
            }
        }
    }
}

private fun resolveAuth(vendor: VendorConfig, provider: String, protocol: ProviderProtocol): ProviderResult<AuthMode> {
    val auth = vendor.authMode ?: defaultAuth(provider, protocol)
    if (!isAuthSupported(provider, protocol, auth)) {
        return providerFailure(ProviderFailureCode.INVALID_AUTH_MODE)
    }
    if (auth in KEY_AUTH && vendor.credential == null) return providerFailure(ProviderFailureCode.MISSING_CREDENTIAL_REFERENCE)
    return ProviderResult.Success(auth)
}

private fun isAuthSupported(provider: String, protocol: ProviderProtocol, auth: AuthMode): Boolean = when {
    provider == "openai_codex" -> auth == AuthMode.OPENAI_CODEX_OAUTH
    auth == AuthMode.OPENAI_CODEX_OAUTH -> false
    protocol == OPENAI_CHAT_COMPLETIONS && ChatCompletionsVendors.supports(provider) -> auth != AuthMode.X_API_KEY
    protocol == OPENAI_RESPONSES && OpenAiResponsesRoutes.supports(provider) -> auth != AuthMode.X_API_KEY
    else -> true
}

private fun defaultAuth(provider: String, protocol: ProviderProtocol): AuthMode = when {
    provider == "openai_codex" -> AuthMode.OPENAI_CODEX_OAUTH
    protocol == ANTHROPIC_MESSAGES || protocol == GOOGLE_GENERATE_CONTENT -> AuthMode.X_API_KEY
    else -> AuthMode.BEARER_API_KEY
}

private fun validateHeaders(vendor: VendorConfig, provider: String): Boolean {
    val normalized = vendor.headers.map { it.name.lowercase() }.toSet()
    val blocked = if (provider == "opencode_go") RESERVED_HEADERS + OPENCODE_HEADERS else RESERVED_HEADERS
    return runCatching { ProviderHeaders.validate(vendor.headers) }.isSuccess && normalized.none { it in blocked }
}

private fun protocolEndpoint(selection: ProviderSelection, base: String, protocol: ProviderProtocol): ProviderResult<String> =
    when (protocol) {
        OPENAI_CHAT_COMPLETIONS -> ProviderResult.Success(openAiEndpoint(base, "/chat/completions"))
        OPENAI_RESPONSES -> ProviderResult.Success(openAiEndpoint(base, "/responses"))
        ANTHROPIC_MESSAGES -> ProviderResult.Success(withSuffix(base, "/messages"))
        GOOGLE_GENERATE_CONTENT -> googleEndpoint(selection, base)
    }

private fun googleEndpoint(selection: ProviderSelection, base: String): ProviderResult<String> {
    val existingVersion = base.substringAfterLast('/').takeIf { it in GOOGLE_VERSIONS }
    val version = selection.runtime.parameters.extraBody["gemini_api_version"] ?: existingVersion ?: "v1"
    if (version !in GOOGLE_VERSIONS) return providerFailure(ProviderFailureCode.INVALID_MODEL_PARAMETERS)
    val modelName = selection.profile.model.trim().removePrefix("models/")
    if (modelName.isBlank()) return providerFailure(ProviderFailureCode.INVALID_MODEL)
    val model = URI(null, null, "/$modelName", null).rawPath.removePrefix("/").replace("/", "%2F")
    val root = existingVersion?.let { base.removeSuffix("/$it") } ?: base
    return ProviderResult.Success("$root/$version/models/$model:streamGenerateContent?alt=sse")
}

private fun openAiEndpoint(base: String, suffix: String): String {
    val existing = OPENAI_SUFFIXES.firstOrNull { base.endsWith(it, ignoreCase = true) }
    val root = existing?.let { base.dropLast(it.length) } ?: base
    return root + suffix
}

private fun withSuffix(base: String, suffix: String): String = if (base.endsWith(suffix, ignoreCase = true)) base else base + suffix

private fun providerBase(endpoint: ProviderNetworkEndpoint, provider: String, protocol: ProviderProtocol): String {
    val base = endpoint.full.trimEnd('/')
    if (provider == "opencode_go" && isOpenCodeRoot(endpoint)) return openCodeBase(protocol)
    if (provider == "ollama" && endpoint.origin.endsWith(":11434") && endpoint.path.trim('/').isEmpty()) {
        return "$base/v1"
    }
    return base
}

private fun isOpenCodeRoot(endpoint: ProviderNetworkEndpoint): Boolean = providerHostname(endpoint.origin) == "opencode.ai" &&
    listOf("/inference/go", "/zen/go").any { prefix -> endpoint.path == prefix || endpoint.path.startsWith("$prefix/") }

private fun openCodeBase(protocol: ProviderProtocol): String = when (protocol) {
    ANTHROPIC_MESSAGES -> "https://opencode.ai/inference/go/anthropic/v1"
    GOOGLE_GENERATE_CONTENT -> "https://opencode.ai/inference/go/google/v1beta"
    else -> "https://opencode.ai/inference/go/openai/v1"
}

private fun builtinBase(provider: String): String = when (provider) {
    "openai" -> "https://api.openai.com/v1"
    "deepseek" -> "https://api.deepseek.com/v1"
    "grok", "xai" -> "https://api.x.ai/v1"
    else -> ""
}

internal fun providerFailure(code: ProviderFailureCode) = ProviderResult.Failure(ProviderFailure(code))

private val KEY_AUTH = setOf(AuthMode.BEARER_API_KEY, AuthMode.X_API_KEY)
private val RESERVED_HEADERS = setOf(
    "authorization",
    "x-api-key",
    "x-goog-api-key",
    "proxy-authorization",
    "host",
    "content-length",
    "transfer-encoding",
)
private val OPENCODE_HEADERS = setOf("user-agent", "x-opencode-session")
private val GOOGLE_VERSIONS = setOf("v1", "v1beta")
private val OPENAI_SUFFIXES = listOf("/chat/completions", "/responses")
