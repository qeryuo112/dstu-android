package com.deepstudent.app.runtime

import com.deepstudent.app.BuildConfig
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.services.providers.api.CodexRequestAuthPort
import com.deepstudent.services.providers.api.OpenAiResponsesRoutes
import com.deepstudent.services.providers.api.ProviderAdapterKey
import com.deepstudent.services.providers.api.ProviderAdapterLocator
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import com.deepstudent.services.providers.backend.ChatCompletionsVendors
import com.deepstudent.services.providers.data.AnthropicProviderFactory
import com.deepstudent.services.providers.data.GeminiNativePorts
import com.deepstudent.services.providers.data.GeminiThoughtSignatureSink
import com.deepstudent.services.providers.data.GoogleGeminiProviderFactory
import com.deepstudent.services.providers.data.OpenAiChatProviderFactory
import com.deepstudent.services.providers.data.OpenAiResponsesProviderFactory
import com.deepstudent.services.providers.data.S308AdapterLocator

/** Application composition; resolution and credential access remain separate. */
internal fun mainProviderAdapters(
    network: NetworkTransport,
    logger: Logger,
    allowLoopback: Boolean,
    codexAuth: CodexRequestAuthPort? = null,
    geminiObserver: GeminiThoughtSignatureSink? = null,
    relayVersion: String = BuildConfig.VERSION_NAME,
): ProviderAdapterLocator {
    val native = nativeProviderAdapters(network, logger, allowLoopback, codexAuth, geminiObserver, relayVersion)
    val relay = S308AdapterLocator(network, logger = logger, allowLoopback = allowLoopback, version = relayVersion)
    return ProviderAdapterLocator { config -> native.find(config) ?: relay.find(config) }
}

private fun nativeProviderAdapters(
    network: NetworkTransport,
    logger: Logger,
    allowLoopback: Boolean,
    codexAuth: CodexRequestAuthPort?,
    geminiObserver: GeminiThoughtSignatureSink?,
    relayVersion: String,
): ProviderAdapterLocator = ProviderAdapterLocator { config ->
    val key = ProviderAdapterKey(config.providerType, config.protocol, config.adapterId)
    when (config.protocol) {
        ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> chatAdapter(config, key, network, logger, allowLoopback)

        ProviderProtocol.OPENAI_RESPONSES -> if (OpenAiResponsesRoutes.supports(key.providerType)) {
            OpenAiResponsesProviderFactory(network, key, logger, allowLoopback, codexAuth, BuildConfig.VERSION_NAME)
        } else {
            null
        }

        ProviderProtocol.ANTHROPIC_MESSAGES -> AnthropicProviderFactory(network, key, logger, allowLoopback, relayVersion)

        ProviderProtocol.GOOGLE_GENERATE_CONTENT -> GoogleGeminiProviderFactory(
            network,
            key.providerType,
            logger,
            allowLoopback,
            native = GeminiNativePorts(signatureSink = geminiObserver),
            appVersion = relayVersion,
        )
    }
}

private fun chatAdapter(
    config: ResolvedProviderConfig,
    key: ProviderAdapterKey,
    network: NetworkTransport,
    logger: Logger,
    allowLoopback: Boolean,
): OpenAiChatProviderFactory? = when {
    key == DIRECT_CHAT_KEY && config.vendor.id == DIRECT_CHAT_VENDOR && config.profile.id == DIRECT_CHAT_PROFILE -> {
        OpenAiChatProviderFactory(network, logger, key, allowLoopback, legacyDirectRequest = true)
    }

    ChatCompletionsVendors.supports(key.providerType) -> OpenAiChatProviderFactory(network, logger, key, allowLoopback)

    else -> null
}

private val DIRECT_CHAT_KEY = ProviderAdapterKey("custom", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "general")
internal const val DIRECT_CHAT_VENDOR = "chat.direct.vendor"
internal const val DIRECT_CHAT_PROFILE = "chat.direct.model"
