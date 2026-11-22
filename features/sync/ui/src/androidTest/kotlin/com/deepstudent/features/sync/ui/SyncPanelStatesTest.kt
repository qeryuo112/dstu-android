package com.deepstudent.features.sync.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Before
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.deepstudent.design.DstuMode
import com.deepstudent.design.DstuTheme
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncConfigurationService
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.api.SyncTransfer
import com.deepstudent.services.sync.api.SyncTransferPhase
import com.deepstudent.services.sync.api.SyncTransferState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test

class SyncPanelStatesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = SyncTestHost()
    @Before fun start() = host.start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun close() { scope.cancel(); host.close() }

    @Test fun lostKeyShowsRecoveryAndOnlyConfirmationRemovesTheBinding() {
        val resets = AtomicInteger()
        val fake = object : SyncConfigurationService {
            override suspend fun current() = SyncResult.Failure(SyncError.CREDENTIAL_KEY_LOST)
            override suspend fun pair(mode: PairingMode, request: PairingRequest) = error("must confirm first")
            override suspend fun clear() = error("ordinary clear cannot reset keys")
            override suspend fun updateEncryptionPassword(password: CharArray) = error("not configured")
            override suspend fun resetCredentialsAfterKeyLoss(): SyncResult<Unit> {
                resets.incrementAndGet()
                return SyncResult.Success(Unit)
            }
        }
        val controller = SyncController(fake, scope)
        host.setContent { DstuTheme(DstuMode.Dark) { SyncPanel(controller) } }
        compose.onNodeWithTag("sync-key-lost").assertTextContains("本机凭据密钥已失效，请重置密钥后重新配对")
        compose.onNodeWithTag("sync-endpoint").assertDoesNotExist()
        compose.onNodeWithTag("sync-key-reset").performClick()
        compose.onNodeWithTag("sync-key-reset-dismiss").performClick()
        org.junit.Assert.assertEquals(0, resets.get())
        compose.onNodeWithTag("sync-key-reset").performClick()
        compose.onNodeWithTag("sync-key-reset-confirm").performClick()
        compose.waitUntil(5_000) { controller.state.value == SyncUiState.Unconfigured }
        org.junit.Assert.assertEquals(1, resets.get())
        compose.onNodeWithTag("sync-endpoint").assertExists()
    }

    @Test fun encryptedConfigurationCorrectsPasswordThroughTheSameTransferControls() {
        val config = SyncConfiguration("https://example.invalid", "fixture", "root", "device", "instance", encrypted = true)
        var accepted = false
        var borrowed: CharArray? = null
        val fake = object : SyncConfigurationService {
            override suspend fun current() = SyncResult.Success(config)
            override suspend fun pair(mode: PairingMode, request: PairingRequest) = error("must not re-pair")
            override suspend fun clear() = error("must keep the configuration")
            override suspend fun updateEncryptionPassword(password: CharArray): SyncResult<Unit> {
                borrowed = password
                accepted = password.contentEquals("public correction".toCharArray())
                return if (accepted) SyncResult.Success(Unit) else SyncResult.Failure(SyncError.ENCRYPTION_PASSWORD_INCORRECT)
            }
        }
        val transfer = object : SyncTransfer {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(SyncTransferState(SyncTransferPhase.IDLE))
            override suspend fun synchronize() = if (accepted) SyncResult.Success(Unit)
                else SyncResult.Failure(SyncError.ENCRYPTION_PASSWORD_INCORRECT)
        }
        val controller = SyncController(fake, scope, { transfer })
        host.setContent { DstuTheme(DstuMode.Dark) { SyncPanel(controller) } }
        compose.waitUntil(5_000) { controller.state.value is SyncUiState.Connected }
        compose.onNodeWithTag("sync-encrypted").assertExists()
        compose.onNodeWithTag("sync-transfer-start").performClick()
        compose.waitUntil(5_000) { controller.transfer.value.phase == SyncTransferPhase.FAILED && !controller.running.value }
        compose.onNodeWithTag("sync-transfer-state").assertTextContains("待上传更改已保留", substring = true)
        compose.onNodeWithTag("sync-encryption-change").performScrollTo().performClick()
        compose.onNodeWithTag("sync-encryption-replacement").performScrollTo().performTextInput("public correction")
        compose.onNodeWithTag("sync-encryption-save").performScrollTo().performClick()
        compose.waitUntil(5_000) { controller.transfer.value.phase == SyncTransferPhase.COMPLETE && !controller.running.value }
        compose.onNodeWithTag("sync-connected").assertExists()
        compose.onNodeWithTag("sync-transfer-state").assertTextContains("同步完成")
        compose.onNodeWithTag("sync-encryption-replacement").assertDoesNotExist()
        compose.runOnIdle { assertTrue(requireNotNull(borrowed).all { it == '\u0000' }) }
    }

    @Test fun lightStatesIncludeLoadingPairingCancellationAndRetry() {
        val current = CompletableDeferred<SyncResult<SyncConfiguration?>>()
        val fake = object : SyncConfigurationService {
            override suspend fun updateEncryptionPassword(password: CharArray) = error("not used")
            var reads = 0
            override suspend fun current(): SyncResult<SyncConfiguration?> =
                if (reads++ == 0) current.await() else SyncResult.Success(null)
            override suspend fun pair(mode: PairingMode, request: PairingRequest): SyncResult<SyncConfiguration> =
                CompletableDeferred<SyncResult<SyncConfiguration>>().await()
            override suspend fun clear(): SyncResult<Unit> = SyncResult.Success(Unit)
        }
        val controller = SyncController(fake, scope)
        host.setContent { DstuTheme(DstuMode.Light) { SyncPanel(controller) } }
        compose.onNodeWithTag("sync-loading").assertExists()
        current.complete(SyncResult.Success(null))
        compose.waitUntil(5_000) { controller.state.value == SyncUiState.Unconfigured }
        compose.onNodeWithTag("sync-endpoint").assertExists()
        compose.onNodeWithTag("sync-create").performScrollTo().performClick()
        compose.onNodeWithTag("sync-cancel").performClick()
        compose.onNodeWithTag("sync-error").assertTextContains("已取消")
        compose.onNodeWithTag("sync-retry").performClick()
        compose.waitUntil(5_000) { controller.state.value == SyncUiState.Unconfigured }
        compose.onNodeWithTag("sync-join").assertExists()
    }

    @Test fun transferShowsProgressCancelAndRetry() {
        val config = SyncConfiguration("https://example.invalid", "user", "root", "device", "instance")
        val fake = object : SyncConfigurationService {
            override suspend fun updateEncryptionPassword(password: CharArray) = error("not used")
            override suspend fun current(): SyncResult<SyncConfiguration?> = SyncResult.Success(config)
            override suspend fun pair(mode: PairingMode, request: PairingRequest): SyncResult<SyncConfiguration> =
                SyncResult.Failure(SyncError.AUTHENTICATION)
            override suspend fun clear(): SyncResult<Unit> = SyncResult.Success(Unit)
        }
        val transfer = object : SyncTransfer {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(
                SyncTransferState(SyncTransferPhase.IDLE),
            )
            override suspend fun synchronize(): SyncResult<Unit> {
                state.value = SyncTransferState(SyncTransferPhase.DOWNLOAD)
                return CompletableDeferred<SyncResult<Unit>>().await()
            }
        }
        val controller = SyncController(fake, scope, { transfer })
        host.setContent { DstuTheme(DstuMode.Dark) { SyncPanel(controller) } }
        compose.waitUntil(5_000) { controller.state.value is SyncUiState.Connected }
        compose.onNodeWithTag("sync-transfer-start").performClick()
        compose.waitUntil(5_000) { controller.transfer.value.phase == SyncTransferPhase.DOWNLOAD }
        compose.onNodeWithTag("sync-transfer-state").assertTextContains("正在下载更改")
        compose.onNodeWithTag("sync-clear").assertDoesNotExist()
        compose.onNodeWithTag("sync-transfer-cancel").performClick()
        compose.waitUntil(5_000) { !controller.running.value }
        compose.onNodeWithTag("sync-transfer-state").assertTextContains("同步失败：已取消")
        compose.onNodeWithTag("sync-transfer-start").assertExists()
    }

    @Test fun connectedClearRequiresConfirmation() {
        val config = SyncConfiguration("https://example.invalid", "user", "root", "device-42", "instance")
        val fake = object : SyncConfigurationService {
            override suspend fun updateEncryptionPassword(password: CharArray) = error("not used")
            var clears = 0
            override suspend fun current(): SyncResult<SyncConfiguration?> = SyncResult.Success(config)
            override suspend fun pair(mode: PairingMode, request: PairingRequest): SyncResult<SyncConfiguration> =
                SyncResult.Failure(SyncError.AUTHENTICATION)
            override suspend fun clear(): SyncResult<Unit> { clears++; return SyncResult.Success(Unit) }
        }
        val controller = SyncController(fake, scope)
        host.setContent { DstuTheme(DstuMode.Dark) { SyncPanel(controller) } }
        compose.waitUntil(5_000) { controller.state.value is SyncUiState.Connected }
        compose.onNodeWithTag("sync-clear").performClick()
        compose.onNodeWithTag("sync-clear-dismiss").performClick()
        org.junit.Assert.assertEquals(0, fake.clears)
        compose.onNodeWithTag("sync-clear").performClick()
        compose.onNodeWithTag("sync-clear-confirm").performClick()
        compose.waitUntil(5_000) { controller.state.value == SyncUiState.Unconfigured }
        org.junit.Assert.assertEquals(1, fake.clears)
        compose.onNodeWithTag("sync-create").assertExists()
    }

    @Test fun darkConnectedShowsDeviceAndErrorHasFixedText() {
        val config = SyncConfiguration("https://example.invalid", "user", "root", "device-42", "instance")
        val fake = object : SyncConfigurationService {
            override suspend fun updateEncryptionPassword(password: CharArray) = error("not used")
            var fail = true
            override suspend fun current(): SyncResult<SyncConfiguration?> =
                if (fail) SyncResult.Failure(SyncError.AUTHENTICATION) else SyncResult.Success(config)
            override suspend fun pair(mode: PairingMode, request: PairingRequest): SyncResult<SyncConfiguration> =
                SyncResult.Failure(SyncError.AUTHENTICATION)
            override suspend fun clear(): SyncResult<Unit> = SyncResult.Success(Unit)
        }
        val controller = SyncController(fake, scope)
        host.setContent { DstuTheme(DstuMode.Dark) { SyncPanel(controller) } }
        compose.onNodeWithTag("sync-error").assertTextContains("认证失败")
        fake.fail = false
        compose.onNodeWithTag("sync-retry").performClick()
        compose.waitUntil(5_000) { controller.state.value is SyncUiState.Connected }
        compose.onNodeWithTag("sync-connected").assertExists()
        compose.onNodeWithTag("sync-device-id").assertTextContains("设备 ID：device-42")
    }
}
