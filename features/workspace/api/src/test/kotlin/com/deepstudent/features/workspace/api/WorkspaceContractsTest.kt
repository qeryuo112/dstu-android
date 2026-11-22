package com.deepstudent.features.workspace.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class WorkspaceContractsTest {
    @Test fun draftBytesAreDefensivelyCopiedAndKeyedByPanel() {
        val panelId = PanelId.parse("panel-1")
        val source = byteArrayOf(1, 2, 3)
        val draft = PanelDraft(panelId, source, revision = 2, updatedAtEpochMs = 10)
        source[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), draft.bytes)
        val read = draft.bytes
        read[1] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), draft.copyBytes())
        assertEquals(panelId, draft.panelId)
        assertNotEquals(draft, PanelDraft(panelId, byteArrayOf(1, 2, 4), 2, 10))
    }

    @Test fun layoutRejectsDuplicateAndDanglingPanels() {
        val panel = PanelInstance(
            id = PanelId.parse("p1"),
            key = WorkspacePanelKeys.Notes,
            resource = ResourceKey("note", "n1"),
            title = "note",
        )
        assertEquals(panel, LayoutState(panels = listOf(panel), activePanelId = panel.id).activePanel())
        assertInvalid { LayoutState(panels = listOf(panel, panel)) }
        assertInvalid { LayoutState(panels = listOf(panel), activePanelId = PanelId.parse("p2")) }
    }

    @Test fun saveUncertainIsPersistablePanelState() {
        val panel = PanelInstance(
            id = PanelId.parse("p1"),
            key = WorkspacePanelKeys.Notes,
            resource = null,
            title = "note",
            dirty = true,
            saveUncertain = true,
        )
        assertEquals(true, LayoutState(panels = listOf(panel)).panels.single().saveUncertain)
    }

    @Test fun resourceIdentityAllowsUrisButRejectsControlCharacters() {
        val pdf = ResourceKey("pdf", "file:///a.pdf")
        assertEquals("[\"pdf\",\"file:///a.pdf\"]", pdf.canonical)
        assertNotEquals(
            ResourceKey("a:b", "c").canonical,
            ResourceKey("a", "b:c").canonical,
        )
        assertInvalid { ResourceKey("pdf", "bad\npath") }
        assertInvalid { ResourceKey("bad type", "x") }
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected validation failure")
        } catch (_: RuntimeException) {
            Unit
        }
    }
}
