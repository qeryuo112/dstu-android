package com.deepstudent.features.sync.ui

import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncConfigurationService
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import com.deepstudent.services.sync.api.SyncTransfer
import com.deepstudent.services.sync.api.SyncTransferPhase
import com.deepstudent.services.sync.api.SyncTransferState
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncControllerTest {
    private class Fake : SyncConfigurationService {
        var passwordUpdate: SyncResult<Unit> = SyncResult.Failure(SyncError.INVALID_CONFIGURATION)
        override suspend fun updateEncryptionPassword(password: CharArray) = passwordUpdate
        var current: SyncResult<SyncConfiguration?> = SyncResult.Success(null)
        var paired: SyncResult<SyncConfiguration> = SyncResult.Failure(SyncError.AUTHENTICATION)
        var wait = false
        var clears = 0
        var resets = 0
        var clearResult: SyncResult<Unit> = SyncResult.Success(Unit)
        var resetResult: SyncResult<Unit> = SyncResult.Success(Unit)
        override suspend fun current() = current
        override suspend fun pair(mode: PairingMode, request: PairingRequest): SyncResult<SyncConfiguration> {
            if (wait) awaitCancellation()
            return paired
        }
        override suspend fun clear(): SyncResult<Unit> { clears++; return clearResult }
        override suspend fun resetCredentialsAfterKeyLoss(): SyncResult<Unit> { resets++; return resetResult }
    }

    @Test fun keyLossRequiresExplicitResetAndWipesBlockedPairingRequest() = runTest {
        val fake = Fake()
        val controller = SyncController(fake, backgroundScope)
        runCurrent()
        controller.resetCredentialsAfterKeyLoss()
        runCurrent()
        assertEquals(0, fake.resets)
        fake.current = SyncResult.Failure(SyncError.CREDENTIAL_KEY_LOST)
        controller.reload()
        runCurrent()
        assertEquals(SyncUiState.KeyRecovery(), controller.state.value)
        assertEquals(0, fake.resets)
        val password = "fixture".toCharArray()
        controller.pair(PairingMode.CREATE, PairingRequest("https://host", "user", "root", password))
        assertEquals(true, password.all { it == '\u0000' })
        fake.resetResult = SyncResult.Failure(SyncError.STORAGE)
        controller.resetCredentialsAfterKeyLoss()
        runCurrent()
        assertEquals(SyncUiState.KeyRecovery(SyncError.STORAGE), controller.state.value)
        fake.resetResult = SyncResult.Success(Unit)
        controller.resetCredentialsAfterKeyLoss()
        runCurrent()
        assertEquals(SyncUiState.Unconfigured, controller.state.value)
        assertEquals(2, fake.resets)
    }

    @Test fun cancelledScopeBeforeLoaderDoesNotLeaveRunning() = runTest {
        val config = SyncConfiguration("endpoint", "user", "root", "device", "instance")
        val fake = Fake().apply { current = SyncResult.Success(config) }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var loaded = false
        val controller = SyncController(fake, scope, { loaded = true; error("not expected") })
        runCurrent()
        scope.cancel()
        controller.synchronize()
        runCurrent()
        assertEquals(false, loaded)
        assertEquals(false, controller.running.value)
        assertEquals(SyncError.CANCELLED, controller.transfer.value.error)
    }

    @Test fun transferSuccessFailureCancellationAndExclusion() = runTest {
        val config = SyncConfiguration("endpoint", "user", "root", "device", "instance")
        val fake = Fake().apply { current = SyncResult.Success(config) }
        var result: SyncResult<Unit> = SyncResult.Success(Unit)
        var wait = false
        var changes = 0
        val transfer = object : SyncTransfer {
            override val state = MutableStateFlow(SyncTransferState(SyncTransferPhase.IDLE))
            override suspend fun synchronize(): SyncResult<Unit> {
                state.value = SyncTransferState(SyncTransferPhase.DOWNLOAD)
                if (wait) awaitCancellation()
                return result
            }
        }
        val controller = SyncController(fake, backgroundScope, { transfer }) { changes++ }
        runCurrent()
        controller.synchronize()
        runCurrent()
        assertEquals(SyncTransferPhase.COMPLETE, controller.transfer.value.phase)
        result = SyncResult.Failure(SyncError.NETWORK)
        controller.synchronize()
        runCurrent()
        assertEquals(SyncError.NETWORK, controller.transfer.value.error)
        wait = true
        controller.synchronize()
        runCurrent()
        assertEquals(SyncTransferPhase.DOWNLOAD, controller.transfer.value.phase)
        controller.clear()
        assertEquals(0, fake.clears)
        controller.cancel()
        runCurrent()
        assertEquals(SyncError.CANCELLED, controller.transfer.value.error)
        assertEquals(false, controller.running.value)
        assertEquals(3, changes)
        controller.clear()
        runCurrent()
        assertEquals(1, fake.clears)
    }

    @Test fun longTransferPublishesProgressAndTerminalResults() = runTest {
        val config = SyncConfiguration("endpoint", "user", "root", "device", "instance")
        val fake = Fake().apply { current = SyncResult.Success(config) }
        var pending = CompletableDeferred<SyncResult<Unit>>()
        val transfer = object : SyncTransfer {
            override val state = MutableStateFlow(SyncTransferState(SyncTransferPhase.IDLE))
            override suspend fun synchronize(): SyncResult<Unit> = pending.await()
        }
        val controller = SyncController(fake, backgroundScope, { transfer })
        runCurrent()
        controller.synchronize()
        runCurrent()
        transfer.state.value = SyncTransferState(SyncTransferPhase.BLOBS)
        runCurrent()
        assertEquals(SyncTransferPhase.BLOBS, controller.transfer.value.phase)
        transfer.state.value = SyncTransferState(SyncTransferPhase.UPLOAD)
        runCurrent()
        assertEquals(SyncTransferPhase.UPLOAD, controller.transfer.value.phase)
        pending.complete(SyncResult.Success(Unit))
        runCurrent()
        assertEquals(SyncTransferPhase.COMPLETE, controller.transfer.value.phase)
        pending = CompletableDeferred()
        controller.synchronize()
        runCurrent()
        transfer.state.value = SyncTransferState(SyncTransferPhase.DOWNLOAD)
        runCurrent()
        assertEquals(SyncTransferPhase.DOWNLOAD, controller.transfer.value.phase)
        pending.complete(SyncResult.Failure(SyncError.NETWORK))
        runCurrent()
        assertEquals(SyncError.NETWORK, controller.transfer.value.error)
        assertEquals(SyncTransferPhase.FAILED, controller.transfer.value.phase)
    }

    @Test fun immediateCancellationClearsSecret() = runTest {
        val fake = Fake()
        val controller = SyncController(fake, backgroundScope)
        runCurrent()
        val secret = "secret".toCharArray()
        controller.pair(PairingMode.CREATE, PairingRequest("endpoint", "user", "root", secret))
        controller.cancel()
        runCurrent()
        assertEquals(SyncUiState.Error(SyncError.CANCELLED), controller.state.value)
        assertEquals(true, secret.all { it == '\u0000' })
    }

    @Test fun cancelledJobDoesNotAllowReplacementBeforeCompletion() = runTest {
        val fake = Fake().apply { wait = true }
        val controller = SyncController(fake, backgroundScope)
        runCurrent()
        val first = "first".toCharArray()
        controller.pair(PairingMode.JOIN, PairingRequest("endpoint", "user", "root", first))
        runCurrent()
        controller.cancel()
        val second = "second".toCharArray()
        controller.pair(PairingMode.CREATE, PairingRequest("endpoint", "user", "root", second))
        assertEquals(true, second.all { it == '\u0000' })
        runCurrent()
        assertEquals(true, first.all { it == '\u0000' })
        assertEquals(SyncUiState.Error(SyncError.CANCELLED), controller.state.value)
    }

    @Test fun clearOnlyWhenConfiguredAndPreservesTypedFailure() = runTest {
        val fake = Fake()
        val config = SyncConfiguration("endpoint", "user", "root", "device", "instance")
        fake.current = SyncResult.Success(config)
        val controller = SyncController(fake, backgroundScope)
        runCurrent()
        fake.clearResult = SyncResult.Failure(SyncError.CREDENTIAL_UNAVAILABLE)
        controller.clear()
        runCurrent()
        assertEquals(SyncUiState.Error(SyncError.CREDENTIAL_UNAVAILABLE), controller.state.value)
        controller.clear()
        assertEquals(1, fake.clears)
        controller.reload()
        runCurrent()
        fake.clearResult = SyncResult.Success(Unit)
        controller.clear()
        runCurrent()
        assertEquals(SyncUiState.Unconfigured, controller.state.value)
        assertEquals(2, fake.clears)
    }

    @Test fun pairingErrorsRetryAndCancellation() = runTest {
        val fake = Fake()
        val controller = SyncController(fake, backgroundScope)
        runCurrent()
        assertEquals(SyncUiState.Unconfigured, controller.state.value)
        val secret = "secret".toCharArray()
        controller.pair(PairingMode.JOIN, PairingRequest("https://host", "user", "root", secret))
        runCurrent()
        assertEquals(SyncUiState.Error(SyncError.AUTHENTICATION), controller.state.value)
        assertEquals(true, secret.all { it == '\u0000' })
        val config = SyncConfiguration("https://host", "user", "root", "device", "instance")
        fake.current = SyncResult.Success(config)
        controller.reload()
        runCurrent()
        assertEquals(SyncUiState.Connected(config), controller.state.value)
        fake.wait = true
        controller.pair(PairingMode.CREATE, PairingRequest("https://host", "user", "root", charArrayOf('x')))
        runCurrent()
        assertEquals(SyncUiState.Pairing, controller.state.value)
        controller.cancel()
        runCurrent()
        assertEquals(SyncUiState.Error(SyncError.CANCELLED), controller.state.value)
    }
}
