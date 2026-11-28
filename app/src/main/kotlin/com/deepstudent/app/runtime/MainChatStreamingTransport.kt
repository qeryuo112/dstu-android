package com.deepstudent.app.runtime

import com.deepstudent.features.chat.api.ChatStreamRequest
import com.deepstudent.features.chat.api.ChatStreamTransport
import com.deepstudent.features.chat.api.ChatTurnEvent
import com.deepstudent.features.chat.api.ChatTurnEventSink
import com.deepstudent.features.chat.api.ChatTurnRequest
import com.deepstudent.features.chat.api.ChatTurnTransport
import com.deepstudent.features.chat.api.ChatTurnUsage
import com.deepstudent.features.chat.api.ChatTurnUsageMetadata
import com.deepstudent.platform.security.CredentialKey
import com.deepstudent.platform.security.CredentialStore
import com.deepstudent.platform.security.CredentialStoreException
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.CredentialReference
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ProviderAdapterLocator
import com.deepstudent.services.providers.api.ProviderCallAccess
import com.deepstudent.services.providers.api.ProviderConfigService
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderCredentialStore
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderUsage
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity
import com.deepstudent.services.providers.api.VendorProtocolDefaults
import kotlinx.coroutines.CancellationException

internal class MainChatStreamingTransport(
    private val legacy: ChatStreamTransport,
    private val providers: ProviderConfigService,
    private val credentials: ProviderCredentialStore,
    private val adapters: ProviderAdapterLocator,
) : ChatTurnTransport {
    override suspend fun collect(request: ChatTurnRequest, sink: ChatTurnEventSink) {
        val model = request.model
        if (model == null) {
            legacy.collect(ChatStreamRequest(request.endpoint, request.prompt)) { sink.emit(ChatTurnEvent.Text(it)) }
            sink.emit(ChatTurnEvent.Completed)
            return
        }
        val selection = select(request, model)
        val result = providers.call(
            selection = selection,
            messages = listOf(ProviderMessage(ProviderRole.USER, request.prompt)),
            sessionId = request.sessionId,
            access = ProviderCallAccess(credentials, adapters),
            events = directTurnEvents(model, sink),
        )
        when (result) {
            is ProviderResult.Success -> {
                result.value.usage?.let { sink.emit(ChatTurnEvent.Usage(it.forModel(model))) }
                sink.emit(ChatTurnEvent.Completed)
            }

            is ProviderResult.Failure -> throw ProviderException(result.failure)

            ProviderResult.Cancelled -> throw CancellationException("provider call cancelled")
        }
    }

    private fun directTurnEvents(model: String, sink: ChatTurnEventSink) = ProviderEventSink { event ->
        when (event) {
            is ProviderStreamEvent.TextDelta -> sink.emit(ChatTurnEvent.Text(event.text))

            is ProviderStreamEvent.UsageReported -> sink.emit(ChatTurnEvent.Usage(event.usage.forModel(model)))

            // This S3.03 text task doesn't reduce additional blocks or dispatch tools.
            is ProviderStreamEvent.ReasoningDelta,
            is ProviderStreamEvent.ToolCallDelta,
            is ProviderStreamEvent.ResponsesMetadata,
            is ProviderStreamEvent.Completed,
            -> Unit
        }
    }

    private fun select(request: ChatTurnRequest, model: String): ProviderSelection {
        val reference = request.credentialReference?.let(::CredentialReference)
        val vendor = VendorConfig(
            identity = VendorIdentity(DIRECT_CHAT_VENDOR, PROVIDER_ID),
            connection = VendorConnection(request.endpoint, if (reference == null) AuthMode.NONE else AuthMode.BEARER_API_KEY, reference),
            protocolDefaults = VendorProtocolDefaults(ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
        )
        return ProviderSelection(vendor, ModelProfile(DIRECT_CHAT_PROFILE, DIRECT_CHAT_VENDOR, model))
    }

    private fun ProviderUsage.forModel(model: String) = ChatTurnUsage(
        metadata = ChatTurnUsageMetadata(PROVIDER_ID, model, DIRECT_CHAT_PROFILE, ProviderProtocol.OPENAI_CHAT_COMPLETIONS.wireName),
        promptTokens = inputTokens,
        completionTokens = outputTokens,
        totalTokens = totalTokens,
        reasoningTokens = reasoningTokens,
        cachedTokens = cachedInputTokens,
        cacheWriteTokens = cacheWriteInputTokens,
    )

    private companion object {
        const val PROVIDER_ID = "custom"
    }
}

internal fun mainProviderCredentials(store: suspend () -> CredentialStore?) = ProviderCredentialStore { ref ->
    if (!ref.key.startsWith("provider.chat.")) return@ProviderCredentialStore missingChatCredential()
    try {
        val secret = store()?.get(CredentialKey.of(ref.key)) ?: return@ProviderCredentialStore missingChatCredential()
        try {
            ProviderResult.Success(secret.use { ProviderCredential.fromBytes(it) })
        } finally {
            secret.close()
        }
    } catch (_: CredentialStoreException) {
        missingChatCredential()
    } catch (_: IllegalArgumentException) {
        missingChatCredential()
    }
}

private fun missingChatCredential() = ProviderResult.Failure(ProviderFailure(ProviderFailureCode.CREDENTIAL_UNAVAILABLE))
