package com.deepstudent.app.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.deepstudent.app.sync.SyncInvalidation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import com.deepstudent.app.ui.NoteEditor
import com.deepstudent.features.notes.data.NoteDraftCodec
import com.deepstudent.features.notes.ui.NoteDraft
import com.deepstudent.features.notes.ui.NotesPanelHost
import com.deepstudent.features.notes.ui.NotesRoute
import com.deepstudent.features.notes.ui.NotesViewModel
import com.deepstudent.features.notes.ui.NotesDestination
import com.deepstudent.features.workspace.api.PanelInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

private class DraftStartup(val draft: NoteDraft?, val failed: Boolean = false)

@Composable
internal fun WorkspaceNotesEntry(ports: WorkspacePorts, panel: PanelInstance, active: Boolean) {
    val startup by produceState<DraftStartup?>(null, panel.id) {
        value = try {
            val restored = withContext(Dispatchers.IO) {
                ports.workspace.loadDraft(panel.id)?.copyBytes()?.toString(Charsets.UTF_8)?.let(NoteDraftCodec::decode)
            }
            DraftStartup(restored?.let { NoteDraft(it.original, it.title, it.content, it.tags, it.editorKey) })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DraftStartup(null, failed = true) }
    }
    val initial = startup ?: return
    if (initial.failed) {
        Text("草稿读取失败，原内容已保留")
        return
    }
    var model by remember(panel.id) { mutableStateOf<NotesViewModel?>(null) }
    val host = remember(ports, panel.id) {
        NotesPanelHost(initial.draft, { ports.drafts.flush(panel.id) }, {
            model = it
            ports.drafts.bind(panel.id, it)
        })
    }
    LaunchedEffect(model) {
        val noteId = panel.resource?.takeIf { it.type == "note" }?.id
        if (initial.draft == null && noteId != null) model?.request(NotesDestination.Open(noteId))
    }
    val generation by SyncInvalidation.generation.collectAsState()
    NotesRoute(ports.notes, {}, editorContent = ::NoteEditor, active = active, host = host, refreshGeneration = generation)
}
