package com.deepstudent.features.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepstudent.features.notes.api.Notes
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** The caller owns [notes]; keep its instance stable for the lifetime of this destination. */
@Composable
fun NotesRoute(
    notes: Notes,
    onExit: () -> Unit,
    diagnostics: NotesUiDiagnostics = NotesUiDiagnostics { },
    editorContent: NotesEditorSlot = ::BasicNotesEditor,
    active: Boolean = true,
    host: NotesPanelHost = NotesPanelHost(),
    refreshGeneration: Long = 0,
) {
    val model: NotesViewModel = viewModel(factory = NotesViewModelFactory(notes, diagnostics, host.initialDraft))
    val state by model.state.collectAsState()
    LaunchedEffect(refreshGeneration) { if (refreshGeneration > 0) model.refreshAfterSync() }
    val currentHost = rememberUpdatedState(host)
    LaunchedEffect(state.draft) {
        try { withContext(NonCancellable) { currentHost.value.onDraftChanged(state.draft) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { model.record(NotesUiEvent.Failed) }
    }
    DisposableEffect(model) { host.onModel(model); onDispose { host.onModel(null) } }
    BackHandler(enabled = active) { if (!model.onBackRequested()) onExit() }
    NotesScreen(state, model::dispatch, editorContent)
}

class NotesPanelHost(
    val initialDraft: NoteDraft? = null,
    val onDraftChanged: suspend (NoteDraft?) -> Unit = {},
    val onModel: (NotesViewModel?) -> Unit = {},
)

private class NotesViewModelFactory(
    private val notes: Notes,
    private val diagnostics: NotesUiDiagnostics,
    private val initialDraft: NoteDraft?,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(NotesViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return NotesViewModel(notes, diagnostics = diagnostics, initialDraft = initialDraft) as T
    }
}
