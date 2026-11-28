package com.deepstudent.services.providers.api

/** Stable resolver contract; implementation lives in services/providers/backend. */
interface ProviderConfigService {
    fun resolve(selection: ProviderSelection): ProviderResult<ResolvedProviderConfig>

    suspend fun call(
        selection: ProviderSelection,
        messages: List<ProviderMessage>,
        tools: List<ProviderTool> = emptyList(),
        sessionId: String? = null,
        cancellation: ProviderCancellation = ProviderCancellation { false },
        access: ProviderCallAccess,
        events: ProviderEventSink = ProviderEventSink { },
    ): ProviderResult<ProviderStreamResult>
}

/** App-owned I/O ports. Both are called only after route validation. */
class ProviderCallAccess(val credentials: ProviderCredentialStore, val adapters: ProviderAdapterLocator)

/** Raw-input validation is a public preflight contract. */
interface ProviderConfigDecoder {
    fun decode(raw: ProviderRawConfig): ProviderResult<ValidatedProviderInput>
    fun decodeSelection(raw: ProviderRawConfig): ProviderResult<ProviderSelection>
}
