package com.deepstudent.features.sync.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Before
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncConfigurationService
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test

class SyncPanelTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = SyncTestHost()
    @Before fun start() = host.start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun close() { scope.cancel(); host.close() }

    @Test fun explicitPairingShowsTypedErrorAndRetry() {
        val fake = object : SyncConfigurationService {
            override suspend fun updateEncryptionPassword(password: CharArray) = error("not used")
            override suspend fun current(): SyncResult<SyncConfiguration?> = SyncResult.Success(null)
            override suspend fun pair(mode: PairingMode, request: PairingRequest): SyncResult<SyncConfiguration> =
                SyncResult.Failure(SyncError.AUTHENTICATION)
            override suspend fun clear(): SyncResult<Unit> = SyncResult.Success(Unit)
        }
        val controller = SyncController(fake, scope)
        host.setContent { SyncPanel(controller) }
        compose.waitUntil(5_000) { controller.state.value == SyncUiState.Unconfigured }
        compose.onNodeWithTag("sync-endpoint").performTextInput("https://example.invalid")
        compose.onNodeWithTag("sync-username").performTextInput("user")
        compose.onNodeWithTag("sync-password").performTextInput("secret")
        compose.onNodeWithTag("sync-join").performClick()
        compose.onNodeWithTag("sync-error").assertExists()
        compose.onNodeWithTag("sync-retry").performClick()
        compose.onNodeWithTag("sync-create").assertExists()
    }
}
