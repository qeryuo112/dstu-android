package com.deepstudent.app.backup

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.services.backup.api.CoverageStatus
import com.deepstudent.services.backup.api.DomainAction
import com.deepstudent.services.backup.api.DomainPreview
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.api.RestorePlanSource
import com.deepstudent.services.backup.api.SnapshotKind
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedBackupEntryUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun passwordIsMaskedEmptySubmitDisabledAndCancelDoesNotAuthenticate() = withScreen { h ->
        compose.onNodeWithTag("encrypted-pick").assertHasClickAction().performClick()
        compose.onNodeWithText("输入备份密码").assertIsDisplayed()
        compose.onNodeWithTag("encrypted-password")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .performTextInput("discarded")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, h.port.prepares.size) }
        compose.onNodeWithTag("encrypted-pick").performClick()
        compose.onNodeWithTag("encrypted-password-submit").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, h.port.prepares.size) }
    }

    @Test fun wrongPasswordCanRetrySameSourceWithoutScheduling() = withScreen { h ->
        h.port.failuresLeft = 1
        compose.onNodeWithTag("encrypted-pick").performClick()
        enterPassword("wrong")
        compose.onNodeWithTag("encrypted-error").assertTextContains("密码不正确", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle {
            assertEquals(1, h.picks.size)
            assertEquals(0, h.port.schedules)
            assertArrayEquals(CharArray(5), h.port.passwords.single())
        }
        compose.onNodeWithTag("encrypted-retry").performClick()
        compose.onNodeWithTag("encrypted-password-submit").assertIsNotEnabled()
        enterPassword("right")
        compose.onNodeWithText("校验通过，尚未恢复").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, h.picks.size)
            assertEquals(listOf(h.source, h.source), h.port.prepares.map { it.first })
            assertEquals(0, h.port.schedules)
            assertArrayEquals(CharArray(5), h.port.passwords.last())
        }
    }

    @Test fun previewExplainsRetainedDomainsAndRequiresExplicitRestoreConfirmation() = withScreen { h ->
        compose.onNodeWithTag("encrypted-pick").performClick()
        enterPassword("pw")
        compose.onNodeWithTag("encrypted-retained").assertTextContains("不会导入或启用", substring = true)
        compose.onNodeWithText("校验通过，尚未恢复").assertIsDisplayed()
        compose.onNodeWithTag("backup-preview").performScrollToIndex(3)
        compose.onNodeWithText("desktop-only:plugins").assertIsDisplayed()
        compose.onNodeWithText("保留在原备份；不导入或启用", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, h.port.schedules) }
        compose.onNodeWithText("安排恢复").performClick()
        compose.onNodeWithText("确认恢复完整快照？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, h.port.schedules) }
        compose.onNodeWithText("安排恢复").performClick()
        compose.onNodeWithText("确认安排").performClick()
        compose.onNodeWithTag("encrypted-result").assertTextContains("下次冷启动", substring = true)
        compose.runOnIdle { assertEquals(1, h.port.schedules) }
    }

    @Test fun unstageablePreviewHasNoRestoreActionAndBackReturnsToEntry() = withScreen { h ->
        h.port.plan = fixturePlan(canStage = false)
        compose.onNodeWithTag("encrypted-pick").performClick()
        enterPassword("pw")
        compose.onNodeWithText("当前不能进入恢复").assertIsDisplayed()
        compose.onNodeWithText("安排恢复").assertDoesNotExist()
        compose.runOnIdle { h.host.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("验证备份密码后可查看能恢复和保留在原备份的数据。").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, h.port.schedules) }
    }

    @Test fun wholePackageExportExplainsSaveAndCancelledPickerDoesNotWrite() = withScreen { h ->
        compose.onNodeWithText("整包加密：加密整个备份 ZIP，打开时需要密码。").assertIsDisplayed()
        compose.onNodeWithTag("encrypted-export-pick").performClick()
        compose.onNodeWithText("请妥善保存密码；忘记密码将无法打开加密内容。").assertIsDisplayed()
        enterPassword("pw")
        compose.onNodeWithTag("encrypted-save-description").assertTextContains("原备份不会修改", substring = true)
            .assertTextContains("deepstudent-encrypted.dsbk", substring = true)
        compose.onNodeWithTag("encrypted-save").performClick() // fake picker cancelled
        compose.runOnIdle {
            assertEquals("application/octet-stream", h.saveChoices.single().mime)
            assertTrue(h.port.exports.isEmpty())
            h.destination = Uri.parse("content://fake/destination.dsbk")
        }
        compose.onNodeWithTag("encrypted-save").performClick()
        compose.onNodeWithTag("encrypted-result").assertTextContains("已保存加密备份", substring = true)
        compose.runOnIdle { assertEquals(listOf(h.destination), h.port.exports) }
    }

    @Test fun desktopZipExplainsPartialEncryptionAndSavesZip() = withScreen { h ->
        compose.onNodeWithText("桌面兼容 ZIP：仅加密敏感子包，其他内容仍是普通 ZIP。").assertIsDisplayed()
        compose.onNodeWithTag("encrypted-portable-export-pick").performClick()
        compose.onNodeWithText("按桌面格式，只对敏感子包加密。其他内容仍是普通 ZIP。").assertIsDisplayed()
        enterPassword("pw")
        compose.onNodeWithTag("encrypted-save-description").assertTextContains("deepstudent-encrypted.zip", substring = true)
        compose.runOnIdle { h.destination = Uri.parse("content://fake/destination.zip") }
        compose.onNodeWithTag("encrypted-save").performClick()
        compose.onNodeWithTag("encrypted-result").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(EncryptedBackupIntent.EXPORT_PORTABLE, h.port.prepares.single().second)
            assertEquals("application/zip", h.saveChoices.single().mime)
            assertEquals(listOf(h.destination), h.port.exports)
        }
    }

    @Test fun workingCanBeCancelledAndDisablesAllSourcePickers() = withScreen { h ->
        h.port.gate = CompletableDeferred()
        compose.onNodeWithTag("encrypted-pick").performClick()
        enterPassword("pw")
        compose.onNodeWithTag("encrypted-pick").assertIsNotEnabled()
        compose.onNodeWithTag("encrypted-export-pick").assertIsNotEnabled()
        compose.onNodeWithTag("encrypted-portable-export-pick").assertIsNotEnabled()
        compose.onNodeWithTag("encrypted-cancel-working").performClick()
        compose.onNodeWithText("验证备份密码后可查看能恢复和保留在原备份的数据。").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, h.port.schedules)
            assertArrayEquals(CharArray(2), h.port.passwords.single())
        }
    }

    @Test fun lightAndDarkExposeButtonAndPasswordSemantics() = withScreen { h ->
        for (dark in listOf(false, true)) {
            compose.runOnIdle { h.dark = dark }
            compose.onNodeWithTag("encrypted-pick").assertIsDisplayed().assertHasClickAction().performClick()
            compose.onNodeWithTag("encrypted-password").assertIsDisplayed()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            compose.onNodeWithTag("encrypted-password-submit").assertIsNotEnabled()
            compose.onNodeWithText("取消").performClick()
        }
    }

    @Test fun activityRecreationClearsDraftPasswordButPreservesPreviewWithoutScheduling() = withScreen { h ->
        compose.onNodeWithTag("encrypted-pick").performClick()
        compose.onNodeWithTag("encrypted-password").performTextInput("discarded")
        recreate(h)
        compose.onNodeWithTag("encrypted-password-submit").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(h.port.prepares.isEmpty()) }
        enterPassword("pw")
        compose.onNodeWithText("校验通过，尚未恢复").assertIsDisplayed()
        recreate(h)
        compose.onNodeWithText("校验通过，尚未恢复").assertIsDisplayed()
        compose.onNodeWithTag("encrypted-retained").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, h.port.schedules) }
    }

    private fun enterPassword(value: String) {
        compose.onNodeWithTag("encrypted-password").performTextInput(value)
        compose.onNodeWithTag("encrypted-password-submit").performClick()
    }

    private fun recreate(h: Harness) {
        val original = h.host.activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync { original.recreate() }
        compose.waitUntil(20_000) { h.host.activity !== original }
        h.mount()
    }

    private fun withScreen(block: (Harness) -> Unit) {
        val h = Harness()
        try {
            h.host.start()
            h.mount()
            block(h)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { h.model.cancel() }
            h.host.close()
        }
    }

    private class Harness {
        val host = EditorTestHost()
        val port = FakeEncryptedActions()
        val model = EncryptedBackupModel(port)
        val source: Uri = Uri.parse("content://fake/source")
        val picks = mutableListOf<EncryptedBackupIntent>()
        val saveChoices = mutableListOf<EncryptedBackupState.ReadyToSave>()
        var destination: Uri? = null
        var dark by mutableStateOf(false)

        fun mount() { host.setContent { Render() } }

        @Composable private fun Render() {
            val state by model.state.collectAsState()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                EncryptedBackupScreen(state, BackupRestoreUiStatus.IDLE, EncryptedBackupScreenActions(
                    pick = { intent -> picks += intent; model.select(source, intent) },
                    save = { choice -> saveChoices += choice; destination?.let(model::save) },
                    cancel = model::cancel,
                    authenticate = model::authenticate,
                    retry = model::retry,
                    confirmRestore = model::confirmRestore,
                ))
            }
        }
    }

    /** Records only in-memory calls. No SAF, crypto, restore, or filesystem writes are performed. */
    private class FakeEncryptedActions : EncryptedBackupActions {
        var plan = fixturePlan(canStage = true)
        var failuresLeft = 0
        var gate: CompletableDeferred<Unit>? = null
        val prepares = mutableListOf<Pair<Uri, EncryptedBackupIntent>>()
        val passwords = mutableListOf<CharArray>()
        val exports = mutableListOf<Uri>()
        var schedules = 0

        override suspend fun prepare(source: Uri, intent: EncryptedBackupIntent, password: CharArray): EncryptedBackupPrepared {
            prepares += source to intent
            passwords += password
            gate?.await()
            if (failuresLeft > 0) {
                failuresLeft--
                throw EncryptedBackupFileException(EncryptedBackupFileError.AUTHENTICATION_FAILED)
            }
            val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val directory = File(cache, "encrypted-ui-fake-${UUID.randomUUID()}")
            return EncryptedBackupPrepared(directory, File(directory, intent.outputName), EncryptedBackupTrace(),
                plan = if (intent == EncryptedBackupIntent.IMPORT) plan else null)
        }

        override suspend fun schedule(prepared: EncryptedBackupPrepared): Boolean { schedules++; return true }
        override suspend fun export(prepared: EncryptedBackupPrepared, destination: Uri) { exports += destination }
    }

    private companion object {
        fun fixturePlan(canStage: Boolean) = RestorePlan(SnapshotKind.FULL, RestorePlanSource(), listOf(
            DomainPreview("database:vfs", CoverageStatus.COMPLETE, DomainAction.STAGE_IN_SLOT, 1, 128),
            DomainPreview("desktop-only:plugins", CoverageStatus.COMPLETE, DomainAction.RETAIN_IN_SOURCE, 1, 64),
        ), emptyList(), 192, emptyList(), canStage)
    }
}
