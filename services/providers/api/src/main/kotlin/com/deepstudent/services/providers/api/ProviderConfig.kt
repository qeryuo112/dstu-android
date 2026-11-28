package com.deepstudent.services.providers.api

import java.util.Collections
import java.util.Locale

/** Identity fields of a vendor. */
data class VendorIdentity(val id: String, val providerType: String, val name: String = providerType) {
    init {
        require(ID.matches(id) && name.trim().isNotEmpty()) { "invalid vendor identity" }
        require(providerType.isNotBlank() && providerType.length <= 64) { "invalid provider type" }
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

/** Endpoint and authentication reference, never a secret value. */
data class VendorConnection(val baseUrl: String, val authMode: AuthMode? = null, val credential: CredentialReference? = null) {
    override fun toString(): String = "VendorConnection(baseUrl=redacted, authMode=$authMode)"
}

/** Persisted vendor-level protocol defaults. A profile may override [protocol]. */
data class VendorProtocolDefaults(val protocol: ProviderProtocol? = null, val supportsOpenaiResponses: Boolean? = null) {
    override fun toString(): String = "VendorProtocolDefaults(protocol=$protocol)"
}

/** Non-secret vendor configuration, grouped to keep public constructors reviewable. */
data class VendorConfig(
    val identity: VendorIdentity,
    val connection: VendorConnection,
    val protocolDefaults: VendorProtocolDefaults = VendorProtocolDefaults(),
    val headers: List<ProviderHeader> = emptyList(),
) {
    init {
        ProviderHeaders.validate(headers)
    }

    val id: String get() = identity.id
    val providerType: String get() = identity.providerType
    val name: String get() = identity.name
    val baseUrl: String get() = connection.baseUrl
    val authMode: AuthMode? get() = connection.authMode
    val credential: CredentialReference? get() = connection.credential
    val protocol: ProviderProtocol? get() = protocolDefaults.protocol
    val supportsOpenaiResponses: Boolean? get() = protocolDefaults.supportsOpenaiResponses

    override fun toString(): String = "VendorConfig(redacted)"
}

data class VendorLimits(
    val maxTokensLimit: Int? = null,
    val rateLimitPerMinute: Int? = null,
    val defaultTimeoutMillis: Long? = null,
    val isBuiltin: Boolean = false,
    val isReadOnly: Boolean = false,
)

/** Non-secret model identity and protocol declaration. */
data class ModelProfile(
    val id: String,
    val vendorId: String,
    val model: String,
    val label: String = model,
    val providerScope: String? = null,
    val protocol: ProviderProtocol? = null,
    val adapter: ModelAdapter = ModelAdapter.GENERAL,
) {
    init {
        require(ID.matches(id) && vendorId.isNotBlank()) { "invalid model identity" }
        require(model.isNotBlank() && model.length <= 512) { "invalid model" }
        require(label.trim().isNotEmpty() && label.length <= 512) { "invalid model label" }
        require(providerScope == null || providerScope.length <= 64) { "invalid provider scope" }
    }

    override fun toString(): String = "ModelProfile(id=$id, model=$model)"

    private companion object {
        val ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

data class ModelRuntime(
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val features: ModelFeatures = ModelFeatures(),
    val reasoning: ReasoningParameters = ReasoningParameters(),
    val parameters: ModelParameters = ModelParameters(),
    val maxTokensLimit: Int? = null,
    val contextWindow: Int? = null,
    val enabled: Boolean = true,
    val status: String = "active",
) {
    val active: Boolean get() = enabled && status.trim().lowercase(Locale.ROOT) != "disabled"

    /** Model family flag, separate from protocol capability. */
    val isReasoning: Boolean get() = reasoning.flags.isReasoning ?: features.reasoning

    /** Per-request thinking switch; defaults to the pre-S3.04 enabled behavior. */
    val thinkingEnabled: Boolean get() = reasoning.flags.thinkingEnabled ?: true
}

data class ProviderSelection(
    val vendor: VendorConfig,
    val profile: ModelProfile,
    val runtime: ModelRuntime = ModelRuntime(),
    val explicitProtocol: ProviderProtocol? = null,
) {
    init {
        require(vendor.id == profile.vendorId) { "profile vendor mismatch" }
    }

    override fun toString(): String = "ProviderSelection(vendor=${vendor.id}, model=${profile.id})"

    val requestedProtocol: ProviderProtocol?
        get() = explicitProtocol ?: profile.protocol ?: vendor.protocol
}

data class ProviderRoute(
    val baseUrl: String,
    val endpoint: String,
    val authMode: AuthMode,
    val credential: CredentialReference?,
    val headers: List<ProviderHeader>,
    val adapterId: String,
) {
    init {
        require(baseUrl.isNotBlank() && endpoint.isNotBlank() && adapterId.isNotBlank())
        ProviderHeaders.validate(headers)
    }

    override fun toString(): String = "ProviderRoute(endpoint=redacted, adapter=$adapterId)"
}

/** Safe resolved data. No credential bytes and no adapter-specific object live here. */
data class ResolvedProviderConfig(
    val vendor: VendorConfig,
    val profile: ModelProfile,
    val runtime: ModelRuntime,
    val providerType: String,
    val providerScope: String,
    val protocol: ProviderProtocol,
    val protocolSource: ProviderProtocolSource,
    val route: ProviderRoute,
) {
    val authMode: AuthMode get() = route.authMode
    val credential: CredentialReference? get() = route.credential
    val headers: List<ProviderHeader> get() = route.headers
    val adapterId: String get() = route.adapterId
    val endpoint: String get() = route.endpoint
    val baseUrl: String get() = route.baseUrl

    init {
        require(providerType.isNotBlank() && providerScope.isNotBlank())
    }

    override fun toString(): String = "ResolvedProviderConfig(provider=$providerType, model=${profile.id}, " +
        "protocol=${protocol.wireName}, auth=${authMode.wireName}, endpoint=redacted)"
}

enum class ProviderRole(val wireName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}

data class ProviderTool(val name: String, val description: String, val inputSchemaJson: String, val strict: Boolean? = null) {
    init {
        require(TOOL_NAME.matches(name)) { "invalid tool name" }
    }

    override fun toString(): String = "ProviderTool(schema=redacted)"

    private companion object {
        val TOOL_NAME = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

/** Adapter request exposes only references and message data, never a key. */
class ProviderAdapterRequest(
    val resolved: ResolvedProviderConfig,
    messages: List<ProviderMessage>,
    tools: List<ProviderTool>,
    val sessionId: String?,
) {
    val messages: List<ProviderMessage> = Collections.unmodifiableList(ArrayList(messages))
    val tools: List<ProviderTool> = Collections.unmodifiableList(ArrayList(tools))

    override fun toString(): String = "ProviderAdapterRequest(redacted)"
}

/** Independent raw preflight result, before this value can become a selection. */
data class ValidatedProviderInput(val vendor: VendorConfig, val profile: ModelProfile, val runtime: ModelRuntime)
