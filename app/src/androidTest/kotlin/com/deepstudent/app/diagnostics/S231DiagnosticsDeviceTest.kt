package com.deepstudent.app.diagnostics

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class S231DiagnosticsDeviceTest {
    private val compose = createEmptyComposeRule()
    private val host = S231AppTestHost()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(S231IsolatedAppRule()).around(compose).around(host)

    @Test
    fun actualActivityShowsRealOperationLoggerEntryAndSurvivesRecreate() {
        val fields = JsonValue.Object.Builder().put("owner", "sync/apply").put("database", "vfs")
            .put("stage", "APPLY").put("libraryId", "private-library")
            .put("authorization", "Bearer SECRET_DEVICE_CANARY").build()
        AndroidOperationLogger.log(
            LogRecord(
                LogLevel.INFO,
                "sync.apply.owner",
                TaskId.parse("private-task"),
                CorrelationId.parse("private-correlation"),
                fields,
            ),
        )
        val entry = S231Diagnostics.buffer.snapshot().entries.last {
            it.event == S231Event.SYNC_APPLY_OWNER && it.location.stage == S231Stage.APPLY
        }
        compose.awaitDiagnosticText("诊断与错误")
        compose.onNodeWithText("诊断与错误").performClick()
        compose.onNodeWithText("任务 T${entry.task} · 关联 C${entry.correlation}", substring = true)
            .performScrollTo().assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previous = host.activity
        instrumentation.runOnMainSync { previous.recreate() }
        compose.waitUntil(20_000) { host.activity !== previous }
        compose.awaitDiagnosticText("分享诊断文件")
        compose.onNodeWithText("分享诊断文件").assertIsDisplayed()
    }

    @Test
    fun tappingShareOpensSystemChooserAndBackCancelsWithoutSending() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain"), null)
        val resolver = checkNotNull(chooser.resolveActivity(context.packageManager)).packageName
        check(resolver != context.packageName)
        compose.awaitDiagnosticText("诊断与错误")
        compose.onNodeWithText("诊断与错误").performClick()
        compose.awaitDiagnosticText("分享诊断文件")
        compose.onNodeWithText("分享诊断文件").performClick()
        try {
            compose.waitUntil(12_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName == resolver }
        } finally {
            // Only press back in the resolved chooser; never choose a receiver or operate another app.
            if (instrumentation.uiAutomation.rootInActiveWindow?.packageName == resolver) {
                val reply = instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
                ParcelFileDescriptor.AutoCloseInputStream(reply).use { it.readBytes() }
            }
        }
        compose.awaitDiagnosticText("分享诊断文件")
        compose.onNodeWithText("分享诊断文件").assertIsDisplayed()
    }

    @Test
    fun fileProviderSharesOnlyProjectedTextWithReadGrant() {
        val sink = S231DiagnosticBuffer()
        val fields = JsonValue.Object.Builder().put("owner", "sync").put("stage", "DOWNLOAD")
            .put("password", "SECRET_DEVICE_CANARY").build()
        sink.log(LogRecord(LogLevel.ERROR, "sync.transfer.finished", fields = fields))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = S231Share.intent(context, sink.snapshot())
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        val uri = requireNotNull(intent.clipData).getItemAt(0).uri
        assertEquals("${context.packageName}.diagnostic-share", uri.authority)
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("stage=DOWNLOAD"))
        assertFalse(text.contains("SECRET_DEVICE_CANARY"))
    }
}
