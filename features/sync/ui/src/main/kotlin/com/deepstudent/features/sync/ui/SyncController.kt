package com.deepstudent.features.sync.ui

import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncConfigurationService
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import com.deepstudent.services.sync.api.SyncTransfer
import com.deepstudent.services.sync.api.SyncTransferPhase
import com.deepstudent.services.sync.api.SyncTransferState

sealed interface SyncUiState {
    data object Loading : SyncUiState
    data object Unconfigured : SyncUiState
    data object Pairing : SyncUiState
    data class Connected(val configuration: SyncConfiguration) : SyncUiState
    data class Error(val error: SyncError) : SyncUiState
    data class KeyRecovery(val resetError: SyncError? = null) : SyncUiState
}

class SyncController(
    private val service: SyncConfigurationService,
    private val scope: CoroutineScope,
    private val transferLoader: (suspend () -> SyncTransfer)? = null,
    private val onTransferFinished: () -> Unit = {},
) {
    private val mutableTransfer = MutableStateFlow(SyncTransferState(SyncTransferPhase.IDLE))
    val transfer: StateFlow<SyncTransferState> = mutableTransfer.asStateFlow()
    val transferAvailable: Boolean get() = transferLoader != null
    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()
    private val mutableState = MutableStateFlow<SyncUiState>(SyncUiState.Loading)
    val state: StateFlow<SyncUiState> = mutableState.asStateFlow()
    private var task: Job? = null

    init { reload() }

    @Synchronized
    fun reload() {
        if (task?.isCompleted == false) return
        task = scope.launch {
            mutableState.value = SyncUiState.Loading
            try {
                mutableState.value = when (val result = service.current()) {
                    is SyncResult.Success -> result.value?.let(SyncUiState::Connected) ?: SyncUiState.Unconfigured
                    is SyncResult.Failure -> configurationFailure(result.error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = SyncUiState.Error(SyncError.STORAGE)
            }
        }
    }

    @Synchronized
    fun pair(mode: PairingMode, request: PairingRequest) {
        if (!canPair()) {
            request.clearSecrets()
            return
        }
        mutableState.value = SyncUiState.Pairing
        task = scope.launch {
            try {
                mutableState.value = when (val result = service.pair(mode, request)) {
                    is SyncResult.Success -> SyncUiState.Connected(result.value)
                    is SyncResult.Failure -> configurationFailure(result.error)
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = SyncUiState.Error(SyncError.CANCELLED)
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = SyncUiState.Error(SyncError.STORAGE)
            } finally {
                request.clearSecrets()
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                request.clearSecrets()
                if (cause is CancellationException && mutableState.value == SyncUiState.Pairing) {
                    mutableState.value = SyncUiState.Error(SyncError.CANCELLED)
                }
            }
        }
    }

    private fun canPair(): Boolean = task?.isCompleted != false && mutableState.value !is SyncUiState.KeyRecovery

    @Synchronized
    fun clear() {
        if (task?.isCompleted == false || mutableState.value !is SyncUiState.Connected) return
        mutableState.value = SyncUiState.Loading
        task = scope.launch {
            try {
                mutableState.value = when (val result = service.clear()) {
                    is SyncResult.Success -> SyncUiState.Unconfigured
                    is SyncResult.Failure -> configurationFailure(result.error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = SyncUiState.Error(SyncError.STORAGE)
            }
        }
    }

    @Synchronized
    fun resetCredentialsAfterKeyLoss() {
        if (task?.isCompleted == false || mutableState.value !is SyncUiState.KeyRecovery) return
        task = scope.launch {
            mutableState.value = SyncUiState.Loading
            try {
                mutableState.value = when (val result = service.resetCredentialsAfterKeyLoss()) {
                    is SyncResult.Success -> {
                        mutableTransfer.value = SyncTransferState(SyncTransferPhase.IDLE)
                        SyncUiState.Unconfigured
                    }
                    is SyncResult.Failure -> SyncUiState.KeyRecovery(result.error)
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = SyncUiState.KeyRecovery()
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = SyncUiState.KeyRecovery(SyncError.STORAGE)
            }
        }
    }

    private fun configurationFailure(error: SyncError): SyncUiState =
        if (error == SyncError.CREDENTIAL_KEY_LOST) SyncUiState.KeyRecovery() else SyncUiState.Error(error)

    @Synchronized
    fun synchronize() {
        val loader = transferLoader ?: return
        if (task?.isCompleted == false || mutableState.value !is SyncUiState.Connected) return
        startTransfer { runTransfer(loader()) }
    }

    @Synchronized
    fun replaceEncryptionPasswordAndRetry(password: CharArray) {
        val current = mutableState.value as? SyncUiState.Connected
        val loader = transferLoader
        if (task?.isCompleted == false || current?.configuration?.encrypted != true || loader == null) {
            password.fill('\u0000')
            return
        }
        startTransfer(password) {
            when (val result = service.updateEncryptionPassword(password)) {
                is SyncResult.Success -> runTransfer(loader())
                is SyncResult.Failure -> mutableTransfer.value = SyncTransferState(SyncTransferPhase.FAILED, error = result.error)
            }
        }
    }

    private fun startTransfer(password: CharArray? = null, execute: suspend () -> Unit) {
        mutableRunning.value = true
        mutableTransfer.value = SyncTransferState(SyncTransferPhase.IDLE)
        task = scope.launch {
            try {
                execute()
            } catch (cancelled: CancellationException) {
                mutableTransfer.value = SyncTransferState(SyncTransferPhase.FAILED, error = SyncError.CANCELLED)
                throw cancelled
            } catch (_: Exception) {
                mutableTransfer.value = SyncTransferState(SyncTransferPhase.FAILED, error = SyncError.STORAGE)
            } finally {
                password?.fill('\u0000')
                if (mutableTransfer.value.error == SyncError.CREDENTIAL_KEY_LOST) {
                    mutableState.value = SyncUiState.KeyRecovery()
                }
                try { onTransferFinished() } finally { mutableRunning.value = false }
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                password?.fill('\u0000')
                if (cause is CancellationException && mutableRunning.value) {
                    mutableTransfer.value = SyncTransferState(SyncTransferPhase.FAILED, error = SyncError.CANCELLED)
                    onTransferFinished()
                    mutableRunning.value = false
                }
            }
        }
    }

    private suspend fun runTransfer(transfer: SyncTransfer) = coroutineScope {
        val observer = launch { transfer.state.collect { mutableTransfer.value = it } }
        try {
            val result = transfer.synchronize()
            mutableTransfer.value = when (result) {
                is SyncResult.Success -> transfer.state.value.copy(phase = SyncTransferPhase.COMPLETE, error = null)
                is SyncResult.Failure -> transfer.state.value.copy(phase = SyncTransferPhase.FAILED, error = result.error)
            }
        } finally {
            observer.cancel()
        }
    }

    @Synchronized
    fun cancel() { task?.cancel() }
}
