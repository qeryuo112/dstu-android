package com.deepstudent.features.notes.ui

import com.deepstudent.features.notes.api.NoteDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

internal fun NotesViewModel.refreshNotes() {
    listJob?.cancel()
    val generation = ++listGeneration
    mutableState.value = state.value.copy(listStatus = NotesListStatus.Loading)
    record(NotesUiEvent.ListStarted)
    listJob = viewModelScopeForNotes().launch {
        try {
            val notes = withContext(io) { notesApi.list(includeDeleted = true) }
            if (generation != listGeneration) return@launch
            mutableState.value = state.value.copy(listStatus = NotesListStatus.Ready, notes = notes.toList())
            record(NotesUiEvent.ListSucceeded)
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive && generation == listGeneration) {
                mutableState.value = state.value.copy(listStatus = NotesListStatus.Cancelled)
                record(NotesUiEvent.ReadCancelled)
            }
            throw cancelled
        } catch (_: Exception) {
            if (generation == listGeneration) {
                mutableState.value = state.value.copy(listStatus = NotesListStatus.Error)
                record(NotesUiEvent.Failed)
            }
        }
    }
}

internal fun NotesViewModel.refreshSyncedNotes() {
    refreshNotes()
    viewModelScopeForNotes().launch {
        try {
            state.first { it.operation == null }
            val original = state.value.draft?.original ?: return@launch
            val remote = withContext(io) { notesApi.get(original.id, includeDeleted = true) }
            state.first { it.operation == null }
            applySyncedNote(original, remote)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { record(NotesUiEvent.Failed) }
    }
}

private fun NotesViewModel.applySyncedNote(original: NoteDocument, remote: NoteDocument?) {
    val current = state.value
    val draft = current.draft ?: return
    if (draft.original != original || current.operation != null || remote == original) return
    if (draft.isDirty || current.conflict || current.writeUncertain || remote == null) {
        mutableState.value = current.copy(feedback = current.feedback.copy(message = "笔记已在同步后更改，请处理冲突", conflict = true))
    } else {
        showNote(remote, newSession = true)
    }
}

internal fun NotesViewModel.openNote(id: String, reload: Boolean = false) {
    detailJob?.cancel()
    val generation = ++detailGeneration
    beginOpen(id, reload)
    record(NotesUiEvent.OpenStarted)
    detailJob = viewModelScopeForNotes().launch {
        try {
            val note = withContext(io) { notesApi.get(id, includeDeleted = true) }
            if (generation != detailGeneration) return@launch
            finishOpen(note)
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive && generation == detailGeneration) {
                mutableState.value = state.value.copy(operation = null, feedback = state.value.feedback.copy(message = "读取已取消"))
                record(NotesUiEvent.ReadCancelled)
            }
            throw cancelled
        } catch (_: Exception) {
            if (generation == detailGeneration) {
                mutableState.value = state.value.copy(operation = null, feedback = state.value.feedback.copy(message = "无法打开笔记，请重试"))
                record(NotesUiEvent.Failed)
            }
        }
    }
}

private fun NotesViewModel.beginOpen(id: String, reload: Boolean) {
    mutableState.value = state.value.copy(
        operation = if (reload) NotesOperation.Reload else NotesOperation.Open, openingId = id,
        feedback = if (reload) state.value.feedback else NotesFeedback(),
        draft = if (reload) state.value.draft else null,
    )
}

private fun NotesViewModel.finishOpen(note: NoteDocument?) {
    if (note == null) {
        mutableState.value = state.value.copy(operation = null, feedback = state.value.feedback.copy(message = "笔记不存在或已被移除"))
    } else {
                showNote(note, newSession = true)
        record(NotesUiEvent.OpenSucceeded)
    }
}

internal fun NotesViewModel.showNote(note: NoteDocument, newSession: Boolean = false) {
    val editorKey = if (newSession) ++editorGeneration else state.value.draft?.editorKey ?: ++editorGeneration
    mutableState.value = state.value.copy(
        draft = NoteDraft(note, note.title, note.content, note.tags.toList(), editorKey),
        collection = if (note.deletedAt == null) NotesCollection.Active else NotesCollection.Trash,
        operation = null, openingId = null, feedback = NotesFeedback(),
    )
}

internal fun NotesViewModel.cancelNotesRead() {
    if (state.value.operation == NotesOperation.Open || state.value.operation == NotesOperation.Reload) {
        detailGeneration++
        detailJob?.cancel()
        mutableState.value = state.value.copy(operation = null, feedback = state.value.feedback.copy(message = "读取已取消"))
        record(NotesUiEvent.ReadCancelled)
    } else if (state.value.listStatus == NotesListStatus.Loading) {
        listGeneration++
        listJob?.cancel()
        mutableState.value = state.value.copy(listStatus = NotesListStatus.Cancelled)
        record(NotesUiEvent.ReadCancelled)
    }
}
