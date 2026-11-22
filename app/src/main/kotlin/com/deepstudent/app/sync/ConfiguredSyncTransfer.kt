package com.deepstudent.app.sync

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.api.SyncTransfer
import com.deepstudent.services.sync.api.SyncTransferPhase
import com.deepstudent.services.sync.api.SyncTransferState
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.ProviderFactory
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.SyncPayloadCodec
import com.deepstudent.services.sync.backend.transfer.SyncTargetLease
import com.deepstudent.services.sync.data.LocalSyncStore
import com.deepstudent.services.sync.data.transfer.StoredEncryptionPassword
import com.deepstudent.services.sync.data.transfer.StoredWebDavProvider
import com.deepstudent.services.sync.data.transfer.SyncProcessLock
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SyncRunGuards(val lockDirectory: File, val validateCapture: () -> Unit)

internal class ConfiguredSyncTransfer(
    private val configuration: LocalSyncStore,
    private val providers: ProviderFactory,
    private val ownerScope: CoroutineScope,
    private val guards: SyncRunGuards,
    private val encryption: RepositoryEncryption,
    private val logger: Logger,
    private val assemble: suspend (SyncConfiguration, ObjectProvider, SyncPayloadCodec) -> SyncTransfer,
) : SyncTransfer {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SyncTransferState(SyncTransferPhase.IDLE))
    override val state: StateFlow<SyncTransferState> = mutableState

    override suspend fun synchronize(): SyncResult<Unit> = mutex.withLock {
        try {
            val config = configuration.read() ?: throw SyncFailure(SyncError.INVALID_CONFIGURATION)
            SyncProcessLock.acquire(guards.lockDirectory).use {
                guards.validateCapture()
                synchronizeConfigured(config)
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = SyncTransferState(SyncTransferPhase.FAILED, error = SyncError.CANCELLED)
            throw cancelled
        } catch (failure: SyncFailure) {
            failed(failure.error)
        } catch (_: Exception) {
            failed(SyncError.STORAGE)
        }
    }

    private suspend fun synchronizeConfigured(config: SyncConfiguration): SyncResult<Unit> =
        StoredEncryptionPassword(configuration).use(config) { password ->
            val remote = StoredWebDavProvider(configuration, providers).open(config)
            // Authenticate before even registering a remote lease or opening the publication journal.
            val policy = encryption.check(remote, config.encrypted, password)
            val lease = SyncTargetLease(remote, config.deviceId, ownerScope).acquire()
            try {
                val guarded = lease.guarded()
                policy.requireUnchanged(guarded)
                observe(assemble(config, policy.guardWrites(guarded), encryption.codec(password)))
            } finally {
                lease.release()
            }
        }

    private suspend fun observe(transfer: SyncTransfer): SyncResult<Unit> = coroutineScope {
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { transfer.state.collect { mutableState.value = it } }
        try {
            transfer.synchronize().also { mutableState.value = transfer.state.value }
        } finally {
            observer.cancel()
        }
    }

    private fun failed(error: SyncError): SyncResult<Unit> {
        mutableState.value = SyncTransferState(SyncTransferPhase.FAILED, error = error)
        logger.info("sync.transfer.preflight_failed", JsonValue.Object.Builder()
            .put("owner", "sync").put("error", error.name).build())
        return SyncResult.Failure(error)
    }
}
