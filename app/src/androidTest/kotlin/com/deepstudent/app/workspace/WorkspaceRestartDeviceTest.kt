package com.deepstudent.app.workspace

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.design.DstuMode
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.chat.api.ChatStreamRequest
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.workspace.api.OpenPanel
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.ResourceKey
import com.deepstudent.features.workspace.api.WorkspaceMode
import com.deepstudent.features.workspace.api.WorkspacePanelKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class WorkspaceRestartDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val root get() = File(context.cacheDir, "s117-four-panel-restart")

    @Test fun writeFourPanelsAndDirtyDraft(): Unit = runBlocking {
        selected("writeFourPanelsAndDirtyDraft")
        clearCaptures()
        root.deleteRecursively()
        WorkspaceTestPorts(context, root).use { owner ->
            WorkspaceChatRuntime(root).use { runtime ->
                ChatSsePeer().use { stream ->
                    val ports = owner.open(runtime.streams)
                    val task = runtime.streams.start(ChatStreamRequest(stream.url, "workspace test"))
                    val note = ports.notes.create(CreateNote("四面板隔离笔记", "原文"))
                    val notesPanel = ports.workspace.state.value.layout.panels.single()
                    ports.workspace.updateResource(notesPanel.id, ResourceKey("note", note.id))
                    val file = File(context.cacheDir, "s117-documents/fixed.pdf").apply { parentFile!!.mkdirs() }
                    instrumentation.context.assets.open("hello_world.pdf").use { input -> file.outputStream().use(input::copyTo) }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.debug.documents", file)
                    val pdfPanel = ports.workspace.open(OpenPanel(WorkspacePanelKeys.Pdf, ResourceKey("pdf", uri.toString())))
                    val chatPanel = ports.workspace.open(OpenPanel(WorkspacePanelKeys.Chat, ResourceKey("chat-task", task.value)))
                    BrowserPagePeer().use { browser ->
                        val browserPanel = ports.workspace.open(
                            OpenPanel(WorkspacePanelKeys.Browser, ResourceKey("browser-url", browser.url)),
                        )
                        ports.workspace.focus(notesPanel.id)
                        val host = EditorTestHost()
                        var dark by mutableStateOf(false)
                        try {
                            host.start()
                            // This is the Activity's reported window width, not proof of tablet hardware:
                            // an external device configuration can also supply a wide window.
                            val windowWidthDp = host.activity.resources.configuration.screenWidthDp
                            val wideWindow = windowWidthDp >= 840
                            var simulatedPhone by mutableStateOf(wideWindow)
                            host.setContent {
                                val config = Configuration(LocalConfiguration.current).apply { if (simulatedPhone) screenWidthDp = 390 }
                                CompositionLocalProvider(LocalConfiguration provides config) {
                                    DstuTheme(if (dark) DstuMode.Dark else DstuMode.Light) {
                                        Surface {
                                            Box(if (simulatedPhone) Modifier.width(390.dp).fillMaxSize() else Modifier.fillMaxSize()) {
                                                FourPanelWorkspace(ports)
                                            }
                                        }
                                    }
                                }
                            }
                            await("editor-input")
                            assertEquals(WorkspaceMode.PhoneStack, ports.workspace.state.value.layout.mode)
                            compose.onNodeWithTag("editor-input").performTextInput(DRAFT)
                            compose.onNodeWithTag("workspace-close-notes").performClick()
                            await("workspace-close-cancel")
                            compose.onNodeWithTag("workspace-close-cancel").performClick()
                            compose.onNodeWithTag("editor-input").assertTextContains(DRAFT + "原文")

                            // The edit/close dialog may leave the virtual keyboard animating on phones.
                            instrumentation.runOnMainSync {
                                val window = host.activity.window
                                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                                    .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                            }
                            // Finish the real SSE while its fixture is still waiting; each check is made
                            // with the real panel focused in PhoneStack, not in a fabricated wide layout.
                            focusPhonePanel(ports, chatPanel)
                            awaitDisplayed("chat-endpoint")
                            compose.waitUntil(10_000) {
                                runCatching {
                                    compose.onNodeWithTag("chat-output").assertTextContains("中文")
                                    true
                                }.getOrDefault(false)
                            }
                            stream.finish()
                            compose.waitUntil(10_000) {
                                runCatching {
                                    compose.onNodeWithTag("chat-output").assertTextContains("中文👩‍💻")
                                    true
                                }.getOrDefault(false)
                            }
                            focusPhonePanel(ports, pdfPanel)
                            awaitDisplayed("pdf-page")
                            focusPhonePanel(ports, browserPanel)
                            awaitDisplayed("workspace-panel-browser")
                            compose.waitUntil(10_000) { browserText(host.activity.window.decorView).contains("Browser panel ready") }
                            focusPhonePanel(ports, notesPanel)
                            awaitDisplayed("editor-input")
                            compose.onNodeWithTag("editor-input").assertTextContains(DRAFT + "原文")
                            capture(if (wideWindow) "phone-simulated-light" else "phone-light")

                            if (wideWindow) {
                                // Restore only the actual reported width; never fake a tablet window on a phone.
                                compose.runOnIdle { simulatedPhone = false }
                                compose.waitUntil(15_000) { ports.workspace.state.value.layout.mode == WorkspaceMode.TabletPanels }
                                await("pdf-page")
                                compose.onNodeWithTag("pdf-page").assertIsDisplayed()
                                compose.onNodeWithTag("chat-endpoint").assertIsDisplayed()
                                compose.onNodeWithTag("chat-output").assertTextContains("中文👩‍💻")
                                compose.onNodeWithTag("workspace-panel-browser").assertIsDisplayed()
                                compose.waitUntil(10_000) { browserText(host.activity.window.decorView).contains("Browser panel ready") }
                                compose.onNodeWithTag("editor-input").assertTextContains(DRAFT + "原文")
                                compose.runOnIdle { dark = true }
                                capture("tablet-dark")
                            } else {
                                compose.runOnIdle { dark = true }
                                capture("phone-dark")
                            }
                            assertEquals(4, ports.workspace.state.value.layout.panels.size)
                            assertTrue(ports.workspace.state.value.layout.panel(notesPanel.id)!!.dirty)
                            assertEquals("原文", ports.notes.get(note.id)!!.content)
                            check(ports.workspace.loadDraft(notesPanel.id) != null)
                            val writePid = android.os.Process.myPid()
                            File(root, "instrumentation-write.pid").writeText(writePid.toString())
                            instrumentation.sendStatus(
                                2,
                                Bundle().apply {
                                    putString("stage", "four-panels.dirty-cancel.persisted")
                                    putInt("activity_window_width_dp", windowWidthDp)
                                    putString("phone_coverage", if (wideWindow) "simulated-390dp" else "reported-phone-window")
                                    putString(
                                        "tablet_coverage",
                                        if (wideWindow) "reported-wide-window-hardware-unverified" else "not-exercised-phone-window",
                                    )
                                    putString("fixture", "instrumentation-hosted-workspace")
                                    putInt("write_pid", writePid)
                                },
                            )
                        } finally {
                            host.close()
                        }
                    }
                }
            }
        }
    }

    @Test fun readAfterForceStopAndSaveOnClose(): Unit = runBlocking {
        selected("readAfterForceStopAndSaveOnClose")
        val writePid = File(root, "instrumentation-write.pid").readText().trim().toInt()
        val readPid = android.os.Process.myPid()
        assertNotEquals("Write and read must run in different processes; force-stop still requires external evidence", writePid, readPid)
        WorkspaceTestPorts(context, root).use { owner ->
            val ports = owner.open()
            assertEquals(4, ports.workspace.state.value.layout.panels.size)
            val panel = ports.workspace.state.value.layout.panels.single { it.key == WorkspacePanelKeys.Notes }
            ports.workspace.focus(panel.id)
            val host = EditorTestHost()
            try {
                host.start()
                host.setContent { DstuTheme { Surface { FourPanelWorkspace(ports) } } }
                await("editor-input")
                compose.onNodeWithTag("editor-input").assertTextContains(DRAFT + "原文")
                compose.onNodeWithTag("workspace-close-notes").performClick()
                await("workspace-close-save")
                compose.onNodeWithTag("workspace-close-save").performClick()
                compose.waitUntil(10_000) { ports.workspace.state.value.layout.panels.none { it.id == panel.id } }
                assertEquals(DRAFT + "原文", ports.notes.list().single().content)
                assertEquals(null, ports.workspace.loadDraft(panel.id))
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString("stage", "four-panels.restart-save.persisted")
                        putInt("write_pid", writePid)
                        putInt("read_pid", readPid)
                        putString("force_stop_evidence", "external-command-required")
                    },
                )
            } finally {
                host.close()
            }
        }
        root.deleteRecursively()
    }

    private fun selected(method: String) {
        assertTrue(
            "Select restart steps individually, with force-stop between write and read",
            InstrumentationRegistry.getArguments().getString("class").orEmpty().endsWith("#$method"),
        )
    }
    private fun focusPhonePanel(ports: WorkspacePorts, panel: PanelInstance) {
        assertEquals(WorkspaceMode.PhoneStack, ports.workspace.state.value.layout.mode)
        compose.onNodeWithTag("workspace-tab-${panel.key.value}").performScrollTo().performClick()
        compose.waitUntil(10_000) { ports.workspace.state.value.layout.activePanelId == panel.id }
    }

    private fun awaitDisplayed(tag: String) {
        compose.waitUntil(12_000) {
            runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
        }
    }

    private fun clearCaptures() {
        listOf("phone-light", "phone-dark", "phone-simulated-light", "tablet-dark").forEach { name ->
            val file = File(context.cacheDir, "s117-workspace-$name.png")
            check(!file.exists() || file.delete()) { "Could not remove stale workspace capture: $file" }
        }
    }

    private fun await(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("native-workspace").captureToImage().asAndroidBitmap()
        File(context.cacheDir, "s117-workspace-$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private companion object {
        const val DRAFT = "未保存中文👩‍💻\n"
    }
}
