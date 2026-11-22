package com.deepstudent.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.app.graph.MigrationRetryState
import com.deepstudent.platform.storage.DataSpaceError
import com.deepstudent.platform.storage.DataSpaceException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataSpaceMaintenanceUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun showsSafeSlotDiagnosisAndRechecksOnlyOnRequest() {
        val retry = MutableStateFlow<MigrationRetryState>(MigrationRetryState.Idle())
        val attempts = AtomicInteger()
        val host = EditorTestHost()
        try {
            host.start()
            host.setContent {
                MainScreen(loadWorkspace = {
                    val error = if (attempts.incrementAndGet() == 1) DataSpaceError.POINTER_MISSING else DataSpaceError.BUSY
                    throw DataSpaceException(error)
                }, retryState = retry, retryMigration = { error("not a migration retry") })
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("dataspace-maintenance").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("诊断代码：POINTER_MISSING").assertIsDisplayed()
            compose.onNodeWithText("未清除原数据", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("dataspace-recheck").performClick()
            compose.onNodeWithText("诊断代码：BUSY").assertIsDisplayed()
        } finally {
            host.close()
        }
    }
}
