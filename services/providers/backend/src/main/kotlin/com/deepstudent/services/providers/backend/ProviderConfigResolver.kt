package com.deepstudent.services.providers.backend

import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ProviderAdapterKey
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderCallAccess
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderConfigService
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderCredentialStore
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRoute
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ProviderTool
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import com.deepstudent.services.providers.api.flatMap
import com.deepstudent.services.providers.api.map
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** Validates a route before a credential read, adapter construction or network request. */
class ProviderConfigResolver(private val registry: ProviderRegistry = ProviderRegistry, private val allowLoopback: Boolean = false) :
    ProviderConfigService {
    override fun resolve(selection: ProviderSelection): ProviderResult<ResolvedProviderConfig> {
        val provider = registry.find(selection.vendor.providerType)
            ?: return providerFailure(ProviderFailureCode.UNKNOWN_PROVIDER)
        if (!selection.runtime.active) return providerFailure(ProviderFailureCode.DISABLED)
        return resolveProtocol(provider, selection).flatMap { protocol ->
            resolveRoute(selection, provider.providerType, protocol.value, allowLoopback).map { route ->
                ResolvedProviderConfig(
                    vendor = selection.vendor,
                    profile = selection.profile,
                    runtime = selection.runtime,
                    providerType = provider.providerType,
                    providerScope = selection.profile.providerScope?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
                        ?: provider.providerType,
                    protocol = protocol.value,
                    protocolSource = protocol.source,
                    route = route,
                )
            }
        }
    }

    override suspend fun call(
        selection: ProviderSelection,
        messages: List<ProviderMessage>,
        tools: List<ProviderTool>,
        sessionId: String?,
        cancellation: ProviderCancellation,
        access: ProviderCallAccess,
        events: ProviderEventSink,
    ): ProviderResult<ProviderStreamResult> = resolve(selection).flatMap { resolved ->
        if (cancellation.isCancelled()) return@flatMap ProviderResult.Cancelled
        val key = ProviderAdapterKey(resolved.providerType, resolved.protocol, resolved.adapterId)
        val factory = access.adapters.find(resolved)?.takeIf { it.key == key }
            ?: return@flatMap providerFailure(ProviderFailureCode.PROTOCOL_ADAPTER_UNAVAILABLE)
        readCredential(resolved, access.credentials).flatMap { secret ->
            try {
                if (cancellation.isCancelled()) return@flatMap ProviderResult.Cancelled
                val request = ProviderAdapterRequest(resolved, messages, tools, sessionId)
                ProviderResult.Success(factory.create(resolved, secret).stream(request, cancellation, events))
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (cancellation.isCancelled()) ProviderResult.Cancelled else throw cancelled
            } catch (failure: ProviderException) {
                if (failure.failure.code ==
                    ProviderFailureCode.CANCELLED
                ) {
                    ProviderResult.Cancelled
                } else {
                    ProviderResult.Failure(failure.failure)
                }
            } finally {
                secret?.close()
            }
        }
    }

    private suspend fun readCredential(
        config: ResolvedProviderConfig,
        credentials: ProviderCredentialStore,
    ): ProviderResult<ProviderCredential?> = when (config.authMode) {
        AuthMode.NONE, AuthMode.OPENAI_CODEX_OAUTH -> ProviderResult.Success(null)

        AuthMode.BEARER_API_KEY, AuthMode.X_API_KEY -> {
            config.credential?.let { credentials.read(it) }
                ?: providerFailure(ProviderFailureCode.MISSING_CREDENTIAL_REFERENCE)
        }
    }
}

internal fun makeRoute(
    selection: ProviderSelection,
    provider: String,
    protocol: ProviderProtocol,
    base: String,
    endpoint: String,
    auth: AuthMode,
) = ProviderRoute(
    baseUrl = base,
    endpoint = endpoint,
    authMode = auth,
    credential = selection.vendor.credential?.takeIf { auth == AuthMode.BEARER_API_KEY || auth == AuthMode.X_API_KEY },
    headers = selection.vendor.headers.toList(),
    adapterId = selectAdapterId(provider, selection.profile, protocol),
)
