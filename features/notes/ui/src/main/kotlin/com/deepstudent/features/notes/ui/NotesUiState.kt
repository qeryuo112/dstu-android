package com.deepstudent.features.notes.ui

import com.deepstudent.features.notes.api.NoteDocument

enum class NotesCollection { Active, Trash }
enum class NotesListStatus { Loading, Ready, Error, Cancelled }
enum class NotesOperation { Open, Reload, Save, Delete, Restore }

data class NoteDraft(
    val original: NoteDocument?,
    val title: String,
    val content: String,
    val tags: List<String>,
    val editorKey: Long = 0,
) {
    val isDirty: Boolean
        get() = if (original == null) {
            title != NEW_NOTE_TITLE || content.isNotEmpty() || tags.isNotEmpty()
        } else {
            title != original.title || content != original.content || tags != original.tags
        }
    val isDeleted: Boolean get() = original?.deletedAt != null
}

internal const val NEW_NOTE_TITLE = "无标题笔记"

sealed interface NotesDestination {
    data object Close : NotesDestination
    data object Create : NotesDestination
    data class Open(val id: String) : NotesDestination
    data class Collection(val value: NotesCollection) : NotesDestination
}

sealed interface NotesDialog {
    data class Discard(val destination: NotesDestination) : NotesDialog
    data object Delete : NotesDialog
    data object ReloadConflict : NotesDialog
}

data class NotesFeedback(val message: String? = null, val conflict: Boolean = false, val writeUncertain: Boolean = false)

data class NotesUiState(
    val collection: NotesCollection = NotesCollection.Active,
    val listStatus: NotesListStatus = NotesListStatus.Loading,
    val notes: List<NoteDocument> = emptyList(),
    val draft: NoteDraft? = null,
    val operation: NotesOperation? = null,
    val openingId: String? = null,
    val dialog: NotesDialog? = null,
    val feedback: NotesFeedback = NotesFeedback(),
) {
    val message: String? get() = feedback.message
    val conflict: Boolean get() = feedback.conflict
    val writeUncertain: Boolean get() = feedback.writeUncertain
    val visibleNotes: List<NoteDocument>
        get() = notes.filter { (it.deletedAt != null) == (collection == NotesCollection.Trash) }
    val showsEditor: Boolean get() = draft != null || operation == NotesOperation.Open ||
        (openingId != null && message != null)
    val canCancelRead: Boolean get() = listStatus == NotesListStatus.Loading ||
        operation == NotesOperation.Open || operation == NotesOperation.Reload
}

enum class NotesUiEvent { ListStarted, ListSucceeded, OpenStarted, OpenSucceeded, SaveStarted, SaveSucceeded,
    DeleteStarted, DeleteSucceeded, RestoreStarted, RestoreSucceeded, ReadCancelled, WriteCancelled, Conflict, Failed }

fun interface NotesUiDiagnostics {
    fun record(event: NotesUiEvent)
}
