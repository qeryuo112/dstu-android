package com.deepstudent.app.sync

import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.features.sync.ui.SyncController
import com.deepstudent.platform.security.SecurityPorts
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.ProviderFactory
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.api.SyncTransfer
import com.deepstudent.services.sync.data.LocalSyncStore
import com.deepstudent.services.sync.data.WebDavProviderFactory
import com.deepstudent.services.sync.data.transfer.StoredWebDavProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal class MainSyncResources internal constructor(val controller: SyncController, private val scope: CoroutineScope) :
    AutoCloseable {
    override fun close() { scope.cancel() }

    companion object {
        fun open(
            directory: File,
            security: SecurityPorts,
            allowLoopbackHttp: Boolean = false,
            encryption: RepositoryEncryption = RepositoryEncryption(),
            transferFactory: (suspend (LocalSyncStore, ProviderFactory, CoroutineScope) -> SyncTransfer)? = null,
        ): MainSyncResources {
            val store = LocalSyncStore(directory, security.credentials)
            val providers = WebDavProviderFactory(allowLoopbackHttp = allowLoopbackHttp)
            val configured = StoredWebDavProvider(store, providers)
            val service = PairingService(store, store, providers, AndroidOperationLogger, encryption, configured::open,
                SyncKeyRecovery(security))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val loader: (suspend () -> SyncTransfer)? = transferFactory?.let { factory ->
                { factory(store, providers, scope) }
            }
            return MainSyncResources(SyncController(service, scope, loader, SyncInvalidation::notifyChanged), scope)
        }
    }
}
