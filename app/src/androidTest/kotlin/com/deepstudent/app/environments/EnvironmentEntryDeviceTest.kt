package com.deepstudent.app.environments

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.app.workspace.FourPanelWorkspace
import com.deepstudent.app.workspace.WorkspaceTestPorts
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.environments.api.*
import com.deepstudent.features.workspace.api.PanelKey
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Every database/journal fixture is below cacheDir; never wipes or imports into the user's filesDir. */
class EnvironmentEntryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 120_000)
    fun packagedOwnerOpensLazilyFromWorkbenchAndSurvivesActivityRecreation(): Unit = runBlocking {
        val root = File(context.cacheDir, "s1-21-native-entry-${System.nanoTime()}")
        val sandbox = object : ContextWrapper(context) { override fun getFilesDir(): File = root }
        val owner = LazyEnvironmentOwner {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MainEnvironmentFactory.open(sandbox) }
        }
        val fixture = WorkspaceTestPorts(context, root)
        val host = EditorTestHost()
        val ports = fixture.open(environments = { owner.get() })
        try {
            assertFalse(owner.isInitialized)
            assertFalse(File(root, "environments").exists())
            host.start()
            host.setContent { DstuTheme { Surface(Modifier.fillMaxSize()) { FourPanelWorkspace(ports) } } }
            awaitTag("workspace-open-environments")
            assertFalse(owner.isInitialized)
            compose.onNodeWithTag("workspace-open-environments").performClick()
            awaitTag("environments-screen")
            assertTrue(owner.isInitialized)
            val manager = owner.get()
            assertEquals(EnvironmentPhase.IDLE, manager.state.value.phase)
            assertTrue(File(root, "environments").isDirectory)
            recreate(host)
            host.setContent { DstuTheme { Surface(Modifier.fillMaxSize()) { FourPanelWorkspace(ports) } } }
            awaitTag("environments-screen")
            assertSame(manager, owner.get())
            compose.onNodeWithTag("workspace-close-environments").performClick()
            compose.waitUntil(10_000) { ports.workspace.state.value.layout.panels.none { it.key == PanelKey.parse("environments") } }
            compose.onNodeWithTag("workspace-open-environments").performClick()
            awaitTag("environments-screen")
            assertSame(manager, owner.get())
            assertEquals(EnvironmentPhase.IDLE, manager.state.value.phase)
        } finally {
            host.close()
            owner.close()
            fixture.close()
            root.deleteRecursively()
        }
    }

    @Test(timeout = 120_000)
    fun fakeRunningTaskIsNotCancelledByCloseAndRestoredWorkspaceReattachesAfterRecreate(): Unit = runBlocking {
        val root = File(context.cacheDir, "s1-21-fake-entry-${System.nanoTime()}")
        val manager = EntryFakeManager()
        val fixture = WorkspaceTestPorts(context, root)
        val host = EditorTestHost()
        var ports = fixture.open(environments = { manager })
        try {
            host.start()
            host.setContent { DstuTheme { Surface(Modifier.fillMaxSize()) { FourPanelWorkspace(ports) } } }
            awaitTag("workspace-open-environments")
            compose.onNodeWithTag("workspace-open-environments").performClick()
            awaitTag("environments-screen")
            compose.onNodeWithText("解包系统").assertIsDisplayed()
            compose.onNodeWithTag("environment-import").assertIsNotEnabled()
            compose.onNodeWithTag("workspace-close-environments").performClick()
            compose.waitUntil(10_000) { ports.workspace.state.value.layout.panels.none { it.key == PanelKey.parse("environments") } }
            assertEquals(0, manager.cancels)
            compose.onNodeWithTag("workspace-open-environments").performClick()
            awaitTag("environments-screen")
            // Reload the actual persisted layout before reattaching the same process-owned task.
            ports = fixture.open(environments = { manager })
            recreate(host)
            host.setContent { DstuTheme { Surface(Modifier.fillMaxSize()) { FourPanelWorkspace(ports) } } }
            awaitTag("environments-screen")
            compose.onNodeWithText("安全解包 12/100").assertIsDisplayed()
            assertEquals(0, manager.cancels)
            capture("s1-environments-entry.png")
            compose.onNodeWithTag("environment-cancel").performClick()
            compose.waitUntil(10_000) { manager.cancels == 1 }
            compose.onNodeWithText("已取消").assertIsDisplayed()
        } finally { host.close(); fixture.close(); root.deleteRecursively() }
    }

    private fun recreate(host: EditorTestHost) {
        val original = host.activity
        instrumentation.runOnMainSync { original.recreate() }
        compose.waitUntil(20_000) { host.activity !== original }
    }
    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(name: String) {
        val image = compose.onNodeWithTag("environments-screen").captureToImage().asAndroidBitmap()
        try { File(context.cacheDir, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }
}

private class EntryFakeManager : EnvironmentManager {
    override val state = MutableStateFlow(EnvironmentSnapshot(phase = EnvironmentPhase.EXTRACTING,
        runtime = EnvironmentRuntimeState(taskId = EnvironmentTaskId("task-entry-fake"),
            progress = EnvironmentProgress(EnvironmentStage.EXTRACT, completedEntries = 12, totalEntries = 100))))
    @Volatile var cancels = 0
    override suspend fun installRootfs(manifest: RootfsManifest, source: RootfsArchiveSource): EnvironmentTaskId = error("UI must not import while busy")
    override suspend fun installPackage(recipeId: String): EnvironmentTaskId = error("UI must not install while busy")
    override suspend fun retry(source: RootfsArchiveSource?): EnvironmentTaskId = error("retry is not part of this fixture")
    override suspend fun cancel(taskId: EnvironmentTaskId) {
        check(taskId == state.value.taskId)
        cancels++
        state.value = state.value.copy(phase = EnvironmentPhase.CANCELLED, runtime = state.value.runtime.copy(
            failure = EnvironmentFailure(EnvironmentFailureCode.CANCELLED, "安装已取消", true), recoverable = true))
    }
}
