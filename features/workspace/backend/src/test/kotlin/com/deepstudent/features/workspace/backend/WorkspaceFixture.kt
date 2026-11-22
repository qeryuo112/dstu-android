package com.deepstudent.features.workspace.backend

import com.deepstudent.core.common.time.RandomSource
import com.deepstudent.features.workspace.api.*
import com.deepstudent.features.workspace.backend.application.PanelHostRegistry
import com.deepstudent.features.workspace.backend.application.WorkspaceSession

internal class WorkspaceFixture {
    val store = Store()
    val host = Host()
    private var id = 0
    val registry = PanelHostRegistry(listOf(host))
    fun session() = WorkspaceSession(registry, store, store, random = RandomSource { size -> ByteArray(size) { (++id).toByte() } })
    suspend fun opened(): Pair<WorkspaceSession, PanelInstance> {
        val session = session()
        session.restore()
        val panel = session.open(OpenPanel(host.key, ResourceKey("note", "one")))
        return session to panel
    }
    class Host : PanelHost {
        override val key = WorkspacePanelKeys.Notes
        override val title = "Notes"
        var saveCalls = 0
        var saveAction: suspend (PanelSaveContext) -> PanelSaveResult = { PanelSaveResult.Saved() }
        var discardAction: suspend (PanelDiscardContext) -> Unit = {}
        override suspend fun save(context: PanelSaveContext): PanelSaveResult { saveCalls++; return saveAction(context) }
        override suspend fun discard(context: PanelDiscardContext) { discardAction(context) }
    }
    class Store : DraftStore, LayoutSnapshotStore {
        var layout: LayoutState? = null
        val drafts = mutableMapOf<PanelId, PanelDraft>()
        var failLoad = false
        var failLayout = false
        var failDelete = false
        override suspend fun load(panelId: PanelId): PanelDraft? {
            check(!failLoad) { "read failure" }
            return drafts[panelId]
        }
        override suspend fun save(draft: PanelDraft): PanelDraft { drafts[draft.panelId] = draft; return draft }
        override suspend fun delete(panelId: PanelId): Boolean { check(!failDelete); return drafts.remove(panelId) != null }
        override suspend fun load(): LayoutState? = layout
        override suspend fun save(layout: LayoutState) { check(!failLayout) { "layout failure" }; this.layout = layout }
        override suspend fun clear() { layout = null }
    }
}
