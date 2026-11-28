package com.deepstudent.services.providers.data

import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.services.providers.api.ProviderAdapterFactory
import com.deepstudent.services.providers.api.ProviderAdapterKey
import com.deepstudent.services.providers.api.ProviderAdapterLocator
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderStreamPort
import com.deepstudent.services.providers.api.ResolvedProviderConfig

/**
 * S3.08 candidate composition: relay Chat Completions and explicit-Responses adapters
 * for custom/general/opencode_go/openrouter/ollama, injected as an independent locator
 * instead of touching the shared official routes. Other members and native protocols use [base].
 */
class S308AdapterLocator(
    private val network: NetworkTransport,
    private val base: ProviderAdapterLocator? = null,
    private val logger: Logger = NoopLogger,
    private val allowLoopback: Boolean = false,
    private val version: String = "0.11.0",
) : ProviderAdapterLocator {
    override fun find(resolved: ResolvedProviderConfig): ProviderAdapterFactory? {
        if (resolved.providerType.trim().lowercase() !in RelayBodyDialect.MEMBERS) return base?.find(resolved)
        return when (resolved.protocol) {
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> S308ChatAdapterFactory(network, key(resolved), logger, allowLoopback, version)
            ProviderProtocol.OPENAI_RESPONSES -> S308ResponsesAdapterFactory(network, key(resolved), logger, allowLoopback, version)
            ProviderProtocol.ANTHROPIC_MESSAGES, ProviderProtocol.GOOGLE_GENERATE_CONTENT -> base?.find(resolved)
        }
    }

    private fun key(resolved: ResolvedProviderConfig) = ProviderAdapterKey(resolved.providerType, resolved.protocol, resolved.adapterId)
}

/** Relay Chat Completions candidate: shared port wrapped by the S3.08 transport boundary. */
internal class S308ChatAdapterFactory(
    private val network: NetworkTransport,
    override val key: ProviderAdapterKey,
    private val logger: Logger = NoopLogger,
    private val allowLoopback: Boolean = false,
    private val version: String = "0.11.0",
) : ProviderAdapterFactory {
    override suspend fun create(resolved: ResolvedProviderConfig, credential: ProviderCredential?): ProviderStreamPort {
        val session = RelaySession()
        val transport = RelayChatTransport(network, resolved, version, session)
        val inner = OpenAiChatProviderFactory(transport, logger, key, allowLoopback).create(resolved, credential)
        return S308RelayChatPort(inner, session, resolved, logger)
    }
}
