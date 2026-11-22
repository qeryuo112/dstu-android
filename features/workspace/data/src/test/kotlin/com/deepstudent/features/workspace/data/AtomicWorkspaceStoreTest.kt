package com.deepstudent.features.workspace.data

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.features.workspace.api.LayoutState
import com.deepstudent.features.workspace.api.PanelDraft
import com.deepstudent.features.workspace.api.PanelId
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.PanelKey
import com.deepstudent.features.workspace.api.ResourceKey
import com.deepstudent.features.workspace.api.WorkspaceMode
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AtomicWorkspaceStoreTest {
    @Test fun draftAndLayoutSurviveRestartWithAtomicReplacement() = runBlocking {
        val root = Files.createTempDirectory("workspace-store").toFile()
        try {
            val panelId = PanelId.parse("panel-note")
            val first = AtomicWorkspaceStore(root)
            first.save(PanelDraft(panelId, "草稿一".toByteArray(), 1, 10))
            first.save(layout(panelId, "笔记一"))
            val second = AtomicWorkspaceStore(root)
            assertEquals("草稿一", second.load(panelId)!!.bytes.toString(Charsets.UTF_8))
            assertEquals("笔记一", second.load()!!.panels.single().title)

            second.save(PanelDraft(panelId, "草稿二".toByteArray(), 2, 20))
            val restarted = AtomicWorkspaceStore(root)
            assertEquals(2, restarted.load(panelId)!!.revision)
            assertArrayEquals("草稿二".toByteArray(), restarted.load(panelId)!!.copyBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun corruptDraftFailsClosedAndIsNotTreatedAsMissing() = runBlocking {
        val root = Files.createTempDirectory("workspace-corrupt").toFile()
        try {
            val panelId = PanelId.parse("panel-note")
            val store = AtomicWorkspaceStore(root)
            store.save(PanelDraft(panelId, byteArrayOf(1, 2, 3), 1, 10))
            val file = root.resolve("drafts").listFiles()!!.single()
            file.writeText("{broken", Charsets.UTF_8)
            try {
                store.load(panelId)
                fail("expected corrupt draft failure")
            } catch (expected: DstuException) {
                assertEquals("WORKSPACE_DRAFT_CORRUPT", expected.error.code)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun clearRemovesLayoutAndDrafts() = runBlocking {
        val root = Files.createTempDirectory("workspace-clear").toFile()
        try {
            val panelId = PanelId.parse("panel-note")
            val store = AtomicWorkspaceStore(root)
            store.save(PanelDraft(panelId, byteArrayOf(1), 1, 1))
            store.save(layout(panelId, "note"))
            store.clear()
            assertNull(store.load())
            assertNull(store.load(panelId))
            assertTrue(root.resolve("drafts").listFiles().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun layout(panelId: PanelId, title: String): LayoutState = LayoutState(
        mode = WorkspaceMode.TabletPanels,
        panels = listOf(
            PanelInstance(
                id = panelId,
                key = PanelKey.parse("notes"),
                resource = ResourceKey("note", "n1"),
                title = title,
                dirty = true,
                draftRevision = 1,
            ),
        ),
        activePanelId = panelId,
        revision = 4,
    )
}
