package com.deepstudent.features.notes.ui

import com.deepstudent.core.common.error.DstuErrorKind
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.features.notes.api.NoteDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun NotesViewModel.saveDraft() {
    val current = state.value
    val draft = current.draft ?: return
    if (!current.maySave(draft)) return
    if (draft.original != null && !draft.isDirty) return
    beginWrite(NotesOperation.Save, NotesUiEvent.SaveStarted) {
        val original = draft.original
        if (original == null) {
            notesApi.create(CreateNote(draft.title, draft.content, draft.tags))
        } else {
            notesApi.edit(EditNote(original.id, original.updatedAt, draft.title, draft.content, draft.tags))
        }
    }
}

private fun NotesUiState.maySave(draft: NoteDraft): Boolean =
    operation == null && dialog == null && !conflict && !writeUncertain && !draft.isDeleted

internal fun NotesViewModel.deleteDraft() {
    val current = state.value
    val id = current.draft?.original?.id ?: return
    if (current.operation != null || current.writeUncertain || current.dialog != NotesDialog.Delete) return
    mutableState.value = current.copy(dialog = null)
    beginWrite(NotesOperation.Delete, NotesUiEvent.DeleteStarted) { notesApi.delete(id) }
}

internal fun NotesViewModel.restoreDraft() {
    val current = state.value
    val draft = current.draft ?: return
    val id = draft.original?.id ?: return
    if (current.operation != null || current.writeUncertain || !draft.isDeleted) return
    beginWrite(NotesOperation.Restore, NotesUiEvent.RestoreStarted) { notesApi.restore(id) }
}

private fun NotesViewModel.beginWrite(
    operation: NotesOperation,
    started: NotesUiEvent,
    command: suspend () -> NoteDocument,
) {
    listGeneration++
    listJob?.cancel()
    mutableState.value = state.value.copy(operation = operation, feedback = NotesFeedback())
    record(started)
    viewModelScopeForNotes().launch {
        try {
            // A dispatched commit can finish after the destination is disposed; do not
            // cancel it between the database commit and observing its result.
            withContext(NonCancellable) {
                val note = withContext(io) { command() }
                showNote(note)
                record(successEvent(operation))
                refresh()
            }
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive) {
                mutableState.value = state.value.copy(
                    operation = null, feedback = NotesFeedback(message = "操作已取消，结果尚未确认，请返回列表检查", writeUncertain = true),
                )
                record(NotesUiEvent.WriteCancelled)
                refresh()
            }
            throw cancelled
        } catch (failure: Exception) {
            writeFailed(operation, failure)
        }
    }
}

private fun NotesViewModel.writeFailed(operation: NotesOperation, failure: Exception) {
    val error = (failure as? DstuException)?.error
    val conflict = operation == NotesOperation.Save && error?.code == "NOTE_CONFLICT"
    val message = when {
        conflict -> "笔记已在别处修改，本地内容尚未保存"
        error?.kind == DstuErrorKind.VALIDATION -> "输入无效，请检查笔记标题"
        error?.code == "NOTE_FORMAT_UNSUPPORTED" -> "此笔记格式暂不支持编辑，原内容已保留"
        else -> "操作失败，请稍后重试"
    }
    mutableState.value = state.value.copy(operation = null, feedback = NotesFeedback(message = message, conflict = conflict))
    record(if (conflict) NotesUiEvent.Conflict else NotesUiEvent.Failed)
    refresh()
}

private fun successEvent(operation: NotesOperation): NotesUiEvent = when (operation) {
    NotesOperation.Save -> NotesUiEvent.SaveSucceeded
    NotesOperation.Delete -> NotesUiEvent.DeleteSucceeded
    NotesOperation.Restore -> NotesUiEvent.RestoreSucceeded
    else -> error("not a write operation")
}
