package com.deepstudent.app.runtime

import com.deepstudent.app.BuildConfig
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.features.chat.api.ChatRepository
import com.deepstudent.features.chat.api.ModelPlaygroundPorts
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.security.CredentialStore
import com.deepstudent.services.providers.api.ProviderCallAccess
import com.deepstudent.services.providers.api.ProviderConfigService
import com.deepstudent.services.providers.backend.ProviderConfigResolver
import com.deepstudent.services.providers.data.codex.CodexAuthSettingsPort
import com.deepstudent.services.providers.data.codex.CodexOAuthService
import com.deepstudent.services.providers.data.vendorModelListPort

/** Provider and account ports for the main process; no UI or other process owns credentials. */
internal class MainProviderRuntime private constructor(
    val config: ProviderConfigService,
    val access: ProviderCallAccess,
    val conversations: MainProviderConversations,
    private val codexService: CodexOAuthService,
    val playground: ModelPlaygroundPorts?,
) : AutoCloseable {
    val codex: CodexAuthSettingsPort get() = codexService
    val codexBrowser = MainCodexBrowserLogin(codex)

    override fun close() {
        codexBrowser.close()
        codexService.close()
    }

    companion object {
        fun create(
            network: NetworkTransport,
            conversations: ChatRepository,
            credentials: suspend () -> CredentialStore?,
        ): MainProviderRuntime {
            val auth = CodexOAuthService(network, MainCodexSessionVault(credentials), logger = AndroidOperationLogger)
            val adapters = mainProviderAdapters(network, AndroidOperationLogger, BuildConfig.DEBUG, auth)
            val config = ProviderConfigResolver(allowLoopback = BuildConfig.DEBUG)
            val access = ProviderCallAccess(mainProviderCredentials(credentials), adapters)
            val chat = MainProviderConversations(conversations, config, access)
            val playground = if (BuildConfig.DEBUG) {
                MainModelPlayground(chat, vendorModelListPort(network, AndroidOperationLogger), access.credentials, allowLoopback = true)
            } else {
                null
            }
            return MainProviderRuntime(config, access, chat, auth, playground)
        }
    }
}
