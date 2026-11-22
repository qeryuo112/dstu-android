package com.deepstudent.app.backup

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.services.backup.api.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupPreviewUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun showsReadOnlyPartialDetailsAndWiresTheFakePicker() {
        val host = EditorTestHost()
        var pickCount = 0
        val plan = RestorePlan(SnapshotKind.PARTIAL_OVERLAY, RestorePlanSource(),
            listOf(DomainPreview("database:chat_v2", CoverageStatus.EXCLUDED, DomainAction.EXCLUDED, 0, 0)),
            emptyList(), 42, listOf(RestoreProblem(RestoreProblemCode.PARTIAL_NOT_SLOTABLE, "not for display")), canStage = false)
        try {
            host.start()
            host.setContent {
                MaterialTheme { BackupPreviewScreen(BackupPreviewState.Report(plan), { pickCount++ }, { }) }
            }
            compose.onNodeWithText("部分覆盖快照", substring = true).assertIsDisplayed()
            compose.onNodeWithText("database:chat_v2").assertIsDisplayed()
            compose.onNodeWithText("当前不能进入恢复").assertIsDisplayed()
            compose.onNodeWithText("选择备份 ZIP").performClick()
            compose.runOnIdle { assertEquals(1, pickCount) }
        } finally { host.close() }
    }

    @Test fun legacyReportShowsSourceAndVersionsAndImportNeedsConfirmation() {
        val host = EditorTestHost()
        var requests = 0
        val plan = fakeLegacyPreview()
        try {
            host.start()
            host.setContent {
                MaterialTheme {
                    Column {
                        BackupRestoreStatusPanel(BackupRestoreUiStatus.IDLE, canSchedule = true, legacyImport = true) { requests++ }
                        Box(Modifier.weight(1f)) { BackupPreviewScreen(BackupPreviewState.Report(plan), {}, {}) }
                    }
                }
            }
            compose.onNodeWithTag("backup-preview").performScrollToIndex(2)
            compose.onNodeWithText("旧移动备份导入报告").assertIsDisplayed()
            compose.onNodeWithText("来源：旧移动端（备份声明）").assertIsDisplayed()
            compose.onNodeWithText("应用 0.20.0 · 清单 3.0.0").assertIsDisplayed()
            compose.onNodeWithText("vfs：20261111").assertIsDisplayed()
            compose.onNodeWithText("旧审计只存档，不替换本机审计。").assertIsDisplayed()
            compose.onNodeWithText("安排导入").performClick()
            compose.onNodeWithText("确认导入旧移动备份？").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            compose.runOnIdle { assertEquals(0, requests) }
            compose.onNodeWithText("安排导入").performClick()
            compose.onNodeWithText("确认安排").performClick()
            compose.runOnIdle { assertEquals(1, requests) }
        } finally { host.close() }
    }

    private fun fakeLegacyPreview() = RestorePlan(
        SnapshotKind.FULL, RestorePlanSource(legacyImport = LegacyImportReport("3.0.0", "0.20.0", mapOf("vfs" to 20261111L))),
        listOf(
            DomainPreview("database:vfs", CoverageStatus.COMPLETE, DomainAction.STAGE_IN_SLOT, 1, 512),
            DomainPreview("asset-root:vfs_blobs", CoverageStatus.COMPLETE, DomainAction.STAGE_IN_SLOT, 1, 8),
            DomainPreview("audit", CoverageStatus.COMPLETE, DomainAction.PRESERVE_INACTIVE, 1, 512),
        ), emptyList(), 1032, emptyList(), canStage = true,
    )
}
