package com.deepstudent.app.runtime

import com.deepstudent.features.chat.api.ModelPlaygroundFailure
import com.deepstudent.features.chat.api.ModelPlaygroundPorts
import com.deepstudent.features.chat.api.ModelPlaygroundTarget
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.CredentialReference
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredentialStore
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderHeader
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity
import com.deepstudent.services.providers.backend.ProviderRegistry
import com.deepstudent.services.providers.data.ModelListException
import com.deepstudent.services.providers.data.OpenCodeGoCatalog
import com.deepstudent.services.providers.data.VendorModelListPort
import com.deepstudent.services.providers.data.VendorModelsAccess
import com.deepstudent.services.providers.data.VendorModelsConnection
import com.deepstudent.services.providers.data.VendorModelsRequest
import kotlinx.coroutines.CancellationException
import java.util.Locale

internal class MainModelPlayground(
    private val conversations: MainProviderConversations,
    private val catalog: VendorModelListPort,
    private val credentials: ProviderCredentialStore,
    private val allowLoopback: Boolean,
) : ModelPlaygroundPorts {
    override suspend fun models(target: ModelPlaygroundTarget): List<String> {
        val request = target.toSelection(discovery = true)
        val vendor = request.vendor
        val protocol = request.explicitProtocol ?: ProviderRegistry.find(vendor.providerType)?.defaultProtocol
        val auth = vendor.authMode ?: defaultCatalogAuth(protocol)
        val access = VendorModelsAccess(auth.wireName, vendor.headers, allowLoopback = allowLoopback)
        val connection = VendorModelsConnection(vendor.id, vendor.providerType, vendor.baseUrl, protocol?.wireName)
        val token = catalogCredential(vendor, auth)
        return try {
            catalog.fetch(VendorModelsRequest(connection, access), token, ProviderCancellation { false }).map { it.id }
        } catch (failure: ModelListException) {
            val code = if (failure.status == 407) "proxy-authentication" else failure.code.name.lowercase(Locale.ROOT)
            throw ModelPlaygroundFailure(code)
        } catch (_: IllegalArgumentException) {
            throw ModelPlaygroundFailure("invalid-config")
        }
    }

    private suspend fun catalogCredential(vendor: VendorConfig, auth: AuthMode): String? {
        if (vendor.providerType == OpenCodeGoCatalog.PROVIDER_TYPE || auth == AuthMode.NONE) return null
        if (auth == AuthMode.OPENAI_CODEX_OAUTH) throw ModelPlaygroundFailure("invalid-auth-mode")
        val reference = vendor.credential ?: throw ModelPlaygroundFailure("missing-credential-reference")
        return when (val lease = credentials.read(reference)) {
            is ProviderResult.Success -> try {
                lease.value.use { it.decodeToString() }
            } finally {
                lease.value.close()
            }

            is ProviderResult.Failure -> throw ModelPlaygroundFailure(lease.failure.code.name.lowercase(Locale.ROOT))

            ProviderResult.Cancelled -> throw CancellationException("catalog cancelled")
        }
    }

    override suspend fun stream(target: ModelPlaygroundTarget, prompt: String, sessionId: String, emit: suspend (String) -> Unit) {
        if (prompt.isBlank() || prompt.length > 32_768) throw ModelPlaygroundFailure("invalid-prompt")
        val selection = target.toSelection(discovery = false)
        val turn = MainProviderTurn(selection, "sess_playground_$sessionId", ProviderMessage(ProviderRole.USER, prompt))
        val result = conversations.call(
            turn,
            events = ProviderEventSink { event ->
                if (event is ProviderStreamEvent.TextDelta) emit(event.text)
            },
        )
        when (result) {
            is ProviderResult.Success -> Unit
            is ProviderResult.Failure -> throw ModelPlaygroundFailure(result.failure.code.name.lowercase(Locale.ROOT).replace('_', '-'))
            ProviderResult.Cancelled -> throw CancellationException("provider call cancelled")
        }
    }
}

private fun ModelPlaygroundTarget.toSelection(discovery: Boolean): ProviderSelection = try {
    val providerType = provider.ifBlank { "custom" }.lowercase(Locale.ROOT)
    val expected = protocol?.let { requireNotNull(ProviderProtocol.fromWire(it)) }
    val auth = authMode?.let { requireNotNull(AuthMode.fromWire(it)) }
    val reference = credentialReference?.let(::CredentialReference)
    val vendor = VendorConfig(
        VendorIdentity("playground-vendor", providerType),
        VendorConnection(endpoint, auth, reference),
        headers = headers.map { (name, value) -> ProviderHeader(name.lowercase(Locale.ROOT), value) },
    )
    ProviderSelection(
        vendor,
        ModelProfile("playground-model", vendor.id, if (discovery) "list-models" else model),
        explicitProtocol = expected,
    )
} catch (_: IllegalArgumentException) {
    throw ModelPlaygroundFailure("invalid-config")
}

private fun defaultCatalogAuth(protocol: ProviderProtocol?): AuthMode = when (protocol) {
    ProviderProtocol.ANTHROPIC_MESSAGES, ProviderProtocol.GOOGLE_GENERATE_CONTENT -> AuthMode.X_API_KEY
    else -> AuthMode.BEARER_API_KEY
}
