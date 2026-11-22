package com.deepstudent.app.terminal

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.app.workspace.FourPanelWorkspace
import com.deepstudent.app.workspace.WorkspaceTestPorts
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.terminal.api.TerminalPhase
import com.deepstudent.features.terminal.api.TerminalSessionId
import com.deepstudent.features.terminal.api.TerminalSessionSnapshot
import com.deepstudent.features.workspace.api.PanelKey
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TerminalEntryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val host = EditorTestHost()

    @Test(timeout = 120_000)
    fun realTerminalRouteSurvivesPanelCloseAndActivityRecreate(): Unit = runBlocking {
        val application = DstuApplication.from(context)
        val root = File(context.cacheDir, "s1-20-entry-isolated-${System.nanoTime()}")
        val sandbox = object : android.content.ContextWrapper(context) { override fun getFilesDir(): File = root }
        val owner = MainTerminalResources.open(sandbox)
        val notes = WorkspaceTestPorts(context, root)
        val sessions = owner.sessions
        val ports = notes.open(terminal = sessions)
        val terminalKey = PanelKey.parse("terminal")

        var created: TerminalSessionId? = null
        try {
            host.start()
            host.setContent {
                DstuTheme {
                    Surface(Modifier.fillMaxSize()) {
                        FourPanelWorkspace(ports)
                    }
                }
            }
            awaitTag("workspace-open-terminal")
            compose.onNodeWithTag("workspace-open-terminal").performClick()
            awaitTag("terminal-screen")
            compose.onNodeWithContentDescription("新建会话").performClick()
            compose.waitUntil(15_000) { sessions.sessions.value.size == 1 }
            awaitTag("terminal-input")

            val id = sessions.sessions.value.single().id
            created = id
            val state = checkNotNull(sessions.observe(id))
            compose.onNodeWithTag("terminal-input").performTextInput("printf '中文😀\\n'")
            compose.onNodeWithContentDescription("发送").performClick()
            compose.waitUntil(15_000) { screenText(state.value).contains("中文😀") }
            awaitContentDescription("中文😀")

            compose.onNodeWithTag("workspace-close-terminal").performClick()
            compose.waitUntil(10_000) {
                ports.workspace.state.value.layout.panels.none { it.key == terminalKey }
            }
            assertEquals(TerminalPhase.RUNNING, state.value.phase)
            assertSame(state, sessions.observe(id))
            assertTrue(sessions.sessions.value.any { it.id == id })

            val firstActivity = host.activity
            instrumentation.runOnMainSync { firstActivity.recreate() }
            compose.waitUntil(20_000) { host.activity !== firstActivity }
            host.setContent {
                DstuTheme {
                    Surface(Modifier.fillMaxSize()) {
                        FourPanelWorkspace(ports)
                    }
                }
            }
            awaitTag("workspace-open-terminal")
            compose.onNodeWithTag("workspace-open-terminal").performClick()
            awaitTag("terminal-screen")

            assertSame(state, sessions.observe(id))
            awaitContentDescription("中文😀")
            captureToCache()

            compose.onNodeWithTag("terminal-input").performTextInput("exit 0")
            compose.onNodeWithContentDescription("发送").performClick()
            compose.waitUntil(15_000) {
                state.value.phase == TerminalPhase.EXITED && state.value.exitCode == 0
            }
            compose.waitForIdle()
            compose.onNodeWithText("已退出，退出码：0").assertExists()

            compose.onNodeWithContentDescription("关闭会话").performClick()
            compose.waitUntil(10_000) { sessions.sessions.value.none { it.id == id } }
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stage", "terminal.real-entry.recreate-reuse-exit0")
            })
        } finally {
            created?.let { id ->
                try {
                    sessions.close(id)
                } catch (_: Exception) {
                    // Process death may have already reclaimed the PTY.
                }
            }
            host.close()
            owner.shutdown()
            notes.close()
            root.deleteRecursively()
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitContentDescription(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun screenText(snapshot: TerminalSessionSnapshot): String =
        snapshot.screen.lines.joinToString("\n") { row ->
            row.joinToString("") { it.text }.trimEnd()
        }

    private fun captureToCache() {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("terminal-screen").captureToImage().asAndroidBitmap()
        try {
            File(context.cacheDir, "s1-terminal-entry.png").outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
