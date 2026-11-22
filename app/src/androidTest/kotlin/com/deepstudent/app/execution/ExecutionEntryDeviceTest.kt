package com.deepstudent.app.execution

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.deepstudent.app.editor.EditorTestHost
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.graph.ProcessRole
import com.deepstudent.app.workspace.FourPanelWorkspace
import com.deepstudent.app.workspace.WorkspacePorts
import com.deepstudent.app.workspace.WorkspaceTestPorts
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.workspace.api.PanelKey
import com.deepstudent.services.execution.api.ExecutionCommand
import com.deepstudent.services.execution.api.ExecutionFailure
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionRequest
import com.deepstudent.services.execution.api.ExecutionResult
import com.deepstudent.services.execution.api.ExecutionSystem
import com.deepstudent.services.execution.api.ShizukuConnectionState
import com.deepstudent.services.execution.api.ShizukuStatus
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The test Activity has no production workspace; every workspace/database fixture is in cacheDir. */
class ExecutionEntryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = PanelKey.parse("shizuku")

    @Test(timeout = 120_000)
    fun fakeSystemIsLazyAndSurvivesPanelCloseAndRestoredActivityRecreation(): Unit = runBlocking {
        val root = File(context.cacheDir, "s1-22-execution-entry-${System.nanoTime()}")
        val fake = EntryFakeSystem()
        val opens = AtomicInteger()
        val owner = LazyExecutionOwner({ ProcessRole.MAIN }) { opens.incrementAndGet(); fake }
        val fixture = WorkspaceTestPorts(context, root)
        var scenario: EditorTestHost? = null
        try {
            val ports = fixture.open(execution = { owner.get() })
            assertFalse(owner.isInitialized)
            val activity = launchActivity().also { scenario = it }
            render(activity, ports)
            awaitTag("workspace-open-shizuku")
            assertFalse(owner.isInitialized)
            openShizuku()
            awaitTag("shizuku-status")
            assertTrue(owner.isInitialized)
            assertEquals(1, opens.get())
            compose.onNodeWithTag("shizuku-verify").performScrollTo().performClick()
            compose.waitUntil(10_000) { fake.lastRequest.get() != null }
            assertEquals(ExecutionCommand.Identity, fake.lastRequest.get()?.command)
            closeShizuku(ports)
            assertEquals(0, fake.closes.get())
            assertEquals(0, fake.stops.get())
            openShizuku()
            awaitTag("shizuku-status")
            assertSame(fake, owner.get())
            val panelId = ports.workspace.state.value.layout.panels.single { it.key == key }.id
            ports.workspace.focus(panelId) // Wait for the original session's pending snapshot write.
            val restored = fixture.open(execution = { owner.get() })
            assertEquals(panelId, restored.workspace.state.value.layout.panels.single { it.key == key }.id)
            val original = activity.activity
            InstrumentationRegistry.getInstrumentation().runOnMainSync { original.recreate() }
            compose.waitUntil(20_000) { activity.activity !== original }
            render(activity, restored)
            awaitTag("shizuku-status")
            assertSame(fake, owner.get())
            assertEquals(1, opens.get())
            assertEquals(0, fake.closes.get())
            assertEquals(0, fake.stops.get())
        } finally {
            scenario?.close()
            owner.close()
            fixture.close()
            root.deleteRecursively()
        }
        assertEquals(1, fake.closes.get())
    }

    @Test(timeout = 120_000)
    fun failedEntryCanRetryWithoutPublishingExceptionContents(): Unit = runBlocking {
        val root = File(context.cacheDir, "s1-22-execution-retry-${System.nanoTime()}")
        val fake = EntryFakeSystem()
        val attempts = AtomicInteger()
        val owner = LazyExecutionOwner({ ProcessRole.MAIN }) {
            if (attempts.incrementAndGet() == 1) error("sensitive fixture value")
            fake
        }
        val fixture = WorkspaceTestPorts(context, root)
        var scenario: EditorTestHost? = null
        try {
            val ports = fixture.open(execution = { owner.get() })
            val activity = launchActivity().also { scenario = it }
            render(activity, ports)
            awaitTag("workspace-open-shizuku")
            openShizuku()
            awaitTag("execution-entry-retry")
            assertFalse(owner.isInitialized)
            compose.onNodeWithText("无法打开 Shizuku，重试").assertIsDisplayed()
            compose.onNodeWithText("sensitive fixture value").assertDoesNotExist()
            compose.onNodeWithTag("execution-entry-retry").performClick()
            awaitTag("shizuku-status")
            assertEquals(2, attempts.get())
            assertSame(fake, owner.get())
        } finally {
            scenario?.close()
            owner.close()
            fixture.close()
            root.deleteRecursively()
        }
        assertEquals(1, fake.closes.get())
    }

    private fun launchActivity(): EditorTestHost = EditorTestHost().also {
        it.start()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            it.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun render(host: EditorTestHost, ports: WorkspacePorts) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            host.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        host.setContent { DstuTheme { Surface(Modifier.fillMaxSize()) { FourPanelWorkspace(ports) } } }
    }

    private fun openShizuku() {
        compose.onNodeWithTag("workspace-open-shizuku").performScrollTo().performClick()
    }

    private fun closeShizuku(ports: WorkspacePorts) {
        compose.onNodeWithTag("workspace-close-shizuku").performScrollTo().performClick()
        compose.waitUntil(10_000) { ports.workspace.state.value.layout.panels.none { it.key == key } }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }
}

private class EntryFakeSystem : ExecutionSystem {
    override val status = MutableStateFlow(ShizukuStatus(
        state = ShizukuConnectionState.READY,
        backendUid = 2000,
        observedUid = 2000,
    ))
    val lastRequest = AtomicReference<ExecutionRequest?>()
    val closes = AtomicInteger()
    val stops = AtomicInteger()
    override fun start() = Unit
    override fun stop() { stops.incrementAndGet() }
    override suspend fun refresh() = Unit
    override fun requestPermission(onResult: (Boolean) -> Unit) = onResult(false)
    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        lastRequest.set(request)
        return ExecutionResult.Failure(ExecutionFailure(ExecutionFailureCode.IO_FAILED))
    }
    override fun close() { closes.incrementAndGet() }
}
