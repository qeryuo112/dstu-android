package com.deepstudent.app.backup

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.app.editor.EditorTestHost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestoreStatusUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun actionMustBeExplicitlyConfirmedAndPendingStateDoesNotOfferRepeat() {
        val host = EditorTestHost()
        var calls = 0
        try {
            host.start()
            host.setContent { MaterialTheme { BackupRestoreStatusPanel(BackupRestoreUiStatus.IDLE, true) { calls++ } } }
            compose.onNodeWithText("安排恢复").performClick()
            compose.onNodeWithText("确认恢复完整快照？").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            compose.runOnIdle { assertEquals(0, calls) }
            compose.onNodeWithText("安排恢复").performClick()
            compose.onNodeWithText("确认安排").performClick()
            compose.runOnIdle { assertEquals(1, calls) }
            host.setContent { MaterialTheme { BackupRestoreStatusPanel(BackupRestoreUiStatus.QUEUED, true) { calls++ } } }
            compose.onNodeWithText("下次冷启动", substring = true).assertIsDisplayed()
            compose.onNodeWithText("安排恢复").assertDoesNotExist()
        } finally { host.close() }
    }
}
