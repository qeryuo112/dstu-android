package com.deepstudent.app.sync

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.material3.Text
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.features.sync.ui.SyncController
import com.deepstudent.features.sync.ui.SyncUiState
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncConfigurationService
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.api.SyncTransfer
import com.deepstudent.services.sync.api.SyncTransferPhase
import com.deepstudent.services.sync.api.SyncTransferState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SyncEntryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun pairingSurvivesActivityRecreation() = runBlocking {
        val pending = CompletableDeferred<SyncResult<SyncConfiguration>>()
        val fake = object : SyncConfigurationService {
            override suspend fun updateEncryptionPassword(password: CharArray) = error("not used")
            override suspend fun current(): SyncResult<SyncConfiguration?> = SyncResult.Success(null)
            override suspend fun pair(mode: PairingMode, request: PairingRequest) = pending.await()
            override suspend fun clear(): SyncResult<Unit> = SyncResult.Success(Unit)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = SyncController(fake, scope)
        val host = EditorTestHost()
        try {
            host.start()
            host.setContent { SyncEntry { controller } }
            awaitTag("sync-create")
            controller.pair(PairingMode.JOIN, PairingRequest("endpoint", "user", "root", charArrayOf('x')))
            awaitTag("sync-cancel")
            val previous = host.activity
            InstrumentationRegistry.getInstrumentation().runOnMainSync { previous.recreate() }
            compose.waitUntil(20_000) { host.activity !== previous }
            host.setContent { SyncEntry { controller } }
            awaitTag("sync-cancel")
            val config = SyncConfiguration("endpoint", "user", "root", "device", "instance")
            pending.complete(SyncResult.Success(config))
            awaitTag("sync-connected")
            assertEquals(SyncUiState.Connected(config), controller.state.value)
        } finally {
            host.close()
            scope.cancel()
        }
    }

    @Test fun transferSurvivesPageRemovalAndReturnsResult() = runBlocking {
        val config = SyncConfiguration("endpoint", "user", "root", "device", "instance")
        val service = object : SyncConfigurationService {
            override suspend fun updateEncryptionPassword(password: CharArray) = error("not used")
            override suspend fun current(): SyncResult<SyncConfiguration?> = SyncResult.Success(config)
            override suspend fun pair(mode: PairingMode, request: PairingRequest): SyncResult<SyncConfiguration> =
                error("not used")
            override suspend fun clear(): SyncResult<Unit> = SyncResult.Success(Unit)
        }
        val pending = CompletableDeferred<SyncResult<Unit>>()
        val transfer = object : SyncTransfer {
            override val state = MutableStateFlow(SyncTransferState(SyncTransferPhase.IDLE))
            override suspend fun synchronize(): SyncResult<Unit> {
                state.value = SyncTransferState(SyncTransferPhase.DOWNLOAD)
                return pending.await()
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = SyncController(service, scope, { transfer })
        val host = EditorTestHost()
        try {
            host.start()
            host.setContent { SyncEntry { controller } }
            awaitTag("sync-transfer-start")
            controller.synchronize()
            awaitTag("sync-transfer-cancel")
            host.setContent { Text("页面已关闭") }
            compose.waitUntil(5_000) { controller.running.value }
            pending.complete(SyncResult.Success(Unit))
            compose.waitUntil(5_000) { !controller.running.value }
            host.setContent { SyncEntry { controller } }
            awaitTag("sync-transfer-start")
            assertEquals(SyncTransferPhase.COMPLETE, controller.transfer.value.phase)
        } finally {
            host.close()
            scope.cancel()
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }
}
