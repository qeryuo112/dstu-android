package com.deepstudent.app.backup

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.api.RestorePlanSource
import com.deepstudent.services.backup.api.SnapshotKind
import com.deepstudent.services.backup.data.BackupIdentityAttempt
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContinuityRestoreUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun reviewMustBeConfirmedAndNeverSchedulesOnDismissal() {
        val host = EditorTestHost()
        val actions = ContinuityUiFixture()
        val model = ContinuityRestoreModel(actions)
        try {
            host.start()
            host.setContent { MaterialTheme { ContinuityRestoreScreen(model, BackupRestoreUiStatus.IDLE) } }
            compose.onNodeWithTag("continuity-issue").performClick()
            compose.waitUntil(5_000) { model.state.value.target != null }
            compose.runOnIdle {
                ContinuityDocument.entries.forEach { model.select(it, Uri.parse("content://fixture/${it.name}")) }
                model.setPin("a".repeat(64))
            }
            compose.onNodeWithTag("continuity-review").performScrollTo().performClick()
            compose.waitUntil(5_000) { model.state.value.review != null }
            compose.onNodeWithTag("continuity-reviewed").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("安排连续迁移").performScrollTo().performClick()
            compose.onNodeWithText("确认保留旧同步身份？").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            compose.runOnIdle { assertEquals(0, actions.scheduled) }
            compose.onNodeWithText("安排连续迁移").performScrollTo().performClick()
            compose.onNodeWithText("确认安排").performClick()
            compose.waitUntil(5_000) { model.state.value.review == null }
            compose.runOnIdle { assertEquals(1, actions.scheduled) }
        } finally { host.close() }
    }
}

private class ContinuityUiFixture : ContinuityEntryActions {
    val target = ContinuityTarget(UUID.randomUUID().toString(), UUID.randomUUID().toString())
    var scheduled = 0
    override suspend fun issueTarget() = target
    override suspend fun renewTarget() = target
    override suspend fun review(documents: Map<ContinuityDocument, Uri>, fingerprint: String): ContinuityReview {
        assertEquals(ContinuityDocument.entries.size, documents.size)
        assertEquals("a".repeat(64), fingerprint)
        val plan = RestorePlan(SnapshotKind.FULL, RestorePlanSource(), emptyList(), emptyList(), 1, emptyList(), true)
        val attempt = BackupIdentityAttempt(target.operationId, target.installationId, "fixture", "retired-demo", "a".repeat(64), "b".repeat(64))
        return ContinuityReview(plan, attempt, "c".repeat(64), 5)
    }
    override suspend fun schedule(documents: Map<ContinuityDocument, Uri>, fingerprint: String, reviewed: ContinuityReview): Boolean {
        assertEquals(target.operationId, reviewed.attempt.operationId)
        scheduled++
        return true
    }
}
