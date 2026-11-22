package com.deepstudent.app.sync

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.app.graph.ProcessRole
import com.deepstudent.app.workspace.FourPanelWorkspace
import com.deepstudent.app.workspace.WorkspacePorts
import com.deepstudent.app.workspace.WorkspaceTestPorts
import com.deepstudent.app.workspace.WorkspaceToolPorts
import com.deepstudent.platform.security.AndroidKeystoreKeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SyncWorkspaceDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 120_000) fun isolatedOwnerIsLazyAndSurvivesPanelAndActivity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir.canonicalFile, "sync-workspace-$id")
        val alias = "com.deepstudent.app.sync.workspace.$id"
        val opens = AtomicInteger()
        var security: com.deepstudent.platform.security.SecurityPorts? = null
        val owner = LazySyncOwner({ ProcessRole.MAIN }) {
            opens.incrementAndGet()
            val ports = SecurityFactory.create(File(root, "security"), alias)
            security = ports
            MainSyncResources.open(File(root, "sync"), ports)
        }
        val fixture = WorkspaceTestPorts(context, File(root, "workspace"))
        val host = EditorTestHost()
        try {
            val base = fixture.open()
            val ports = WorkspacePorts(base.workspace, base.notes, base.chat, base.pdf, base.drafts,
                tools = WorkspaceToolPorts(sync = { owner.get().controller }))
            assertFalse(owner.isInitialized)
            assertEquals(0, opens.get())
            host.start()
            host.setContent { FourPanelWorkspace(ports) }
            awaitTag("workspace-open-sync")
            assertFalse(owner.isInitialized)
            click("workspace-open-sync")
            awaitTag("sync-create")
            val first = owner.get()
            assertTrue(owner.isInitialized)
            assertEquals(1, opens.get())
            click("workspace-close-sync")
            awaitTag("workspace-open-sync")
            assertSame(first, owner.get())
            click("workspace-open-sync")
            awaitTag("sync-create")
            val previous = host.activity
            InstrumentationRegistry.getInstrumentation().runOnMainSync { previous.recreate() }
            compose.waitUntil(20_000) { host.activity !== previous }
            host.setContent { FourPanelWorkspace(ports) }
            awaitTag("sync-create")
            assertSame(first, owner.get())
            assertEquals(1, opens.get())
        } finally {
            host.close()
            owner.close()
            security?.close()
            fixture.close()
            check(alias != AndroidKeystoreKeyWrapper.DEFAULT_ALIAS)
            AndroidKeystoreKeyWrapper(alias).deleteKey()
            root.deleteRecursively()
        }
    }

    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }
}
