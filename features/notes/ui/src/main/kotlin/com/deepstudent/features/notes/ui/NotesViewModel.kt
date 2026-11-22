package com.deepstudent.features.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepstudent.core.ui.BackRequestHandler
import com.deepstudent.features.notes.api.Notes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NotesViewModel(
    internal val notesApi: Notes,
    internal val io: CoroutineDispatcher = Dispatchers.IO,
    internal val diagnostics: NotesUiDiagnostics = NotesUiDiagnostics { },
    initialDraft: NoteDraft? = null,
) : ViewModel(), BackRequestHandler {
    internal val mutableState = MutableStateFlow(NotesUiState(draft = initialDraft))
    val state: StateFlow<NotesUiState> = mutableState.asStateFlow()
    internal var listJob: Job? = null
    internal var detailJob: Job? = null
    internal var listGeneration = 0
    internal var detailGeneration = 0
    internal var editorGeneration = initialDraft?.editorKey ?: 0L

    init { refresh() }

    internal fun viewModelScopeForNotes(): CoroutineScope = viewModelScope

    fun refresh() = refreshNotes()
    fun refreshAfterSync() = refreshSyncedNotes()
    fun cancelRead() = cancelNotesRead()
    fun save() = saveDraft()
    fun confirmDelete() = deleteDraft()
    fun restore() = restoreDraft()
    fun retryOpen(id: String) = request(NotesDestination.Open(id))

    fun changeTitle(title: String) = editDraft { copy(title = title) }
    fun changeContent(content: String) = editDraft { copy(content = content) }

    fun request(destination: NotesDestination) {
        val current = state.value
        if (current.operation != null || current.dialog != null) return
        if (destination is NotesDestination.Open && current.draft?.original?.id == destination.id) return
        if (destination is NotesDestination.Collection && current.collection == destination.value) return
        if (current.draft?.isDirty == true) {
            mutableState.value = current.copy(dialog = NotesDialog.Discard(destination))
        } else {
            navigate(destination)
        }
    }

    override fun onBackRequested(): Boolean {
        val current = state.value
        if (current.dialog != null) {
            mutableState.value = current.copy(dialog = null)
            return true
        }
        if (current.operation == NotesOperation.Open || current.operation == NotesOperation.Reload) {
            cancelRead()
            return true
        }
        if (current.operation != null) return true
        if (current.draft == null && current.openingId != null) {
            mutableState.value = current.copy(openingId = null, feedback = NotesFeedback())
            return true
        }
        if (current.draft == null) return backFromCollection(current)
        request(NotesDestination.Close)
        return true
    }

    private fun backFromCollection(current: NotesUiState): Boolean {
        if (current.collection == NotesCollection.Active) return false
        mutableState.value = current.copy(collection = NotesCollection.Active, feedback = NotesFeedback())
        return true
    }

    fun discardAndContinue() {
        val dialog = state.value.dialog as? NotesDialog.Discard ?: return
        mutableState.value = state.value.copy(dialog = null)
        navigate(dialog.destination)
    }

    fun dismissDialog() { mutableState.value = state.value.copy(dialog = null) }

    fun askDelete() {
        if (state.value.operation == null && state.value.draft?.original != null) {
            mutableState.value = state.value.copy(dialog = NotesDialog.Delete)
        }
    }

    fun askReloadConflict() {
        if (state.value.conflict && state.value.operation == null) {
            mutableState.value = state.value.copy(dialog = NotesDialog.ReloadConflict)
        }
    }

    fun reloadConflict() {
        val id = state.value.draft?.original?.id ?: return
        mutableState.value = state.value.copy(dialog = null)
        openNote(id, reload = true)
    }

    internal fun navigate(destination: NotesDestination) {
        when (destination) {
            NotesDestination.Close -> mutableState.value = state.value.copy(
                draft = null, openingId = null, feedback = NotesFeedback(),
            )
            NotesDestination.Create -> mutableState.value = state.value.copy(
                collection = NotesCollection.Active,
                draft = NoteDraft(null, NEW_NOTE_TITLE, "", emptyList(), ++editorGeneration),
                openingId = null,
                feedback = NotesFeedback(),
            )
            is NotesDestination.Open -> openNote(destination.id)
            is NotesDestination.Collection -> mutableState.value = state.value.copy(
                collection = destination.value, draft = null, openingId = null,
                feedback = NotesFeedback(),
            )
        }
    }

    private fun editDraft(change: NoteDraft.() -> NoteDraft) {
        val current = state.value
        val draft = current.draft ?: return
        if (current.operation != null || draft.isDeleted) return
        val feedback = if (current.conflict || current.writeUncertain) current.feedback else NotesFeedback()
        mutableState.value = current.copy(draft = draft.change(), feedback = feedback)
    }

    internal fun record(event: NotesUiEvent) {
        try { diagnostics.record(event) } catch (_: Exception) { /* Diagnostics cannot change a user action. */ }
    }
}
