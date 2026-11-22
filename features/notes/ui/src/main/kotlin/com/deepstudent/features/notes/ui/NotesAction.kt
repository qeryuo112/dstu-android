package com.deepstudent.features.notes.ui

sealed interface NotesAction {
    data object Refresh : NotesAction
    data object CancelRead : NotesAction
    data object Save : NotesAction
    data object AskDelete : NotesAction
    data object Delete : NotesAction
    data object Restore : NotesAction
    data object Back : NotesAction
    data object Discard : NotesAction
    data object DismissDialog : NotesAction
    data object AskReload : NotesAction
    data object Reload : NotesAction
    data class Navigate(val destination: NotesDestination) : NotesAction
    data class Title(val value: String) : NotesAction
    data class Content(val value: String) : NotesAction
}

internal fun NotesViewModel.dispatch(action: NotesAction) {
    when (action) {
        is NotesAction.Navigate -> request(action.destination)
        is NotesAction.Title -> changeTitle(action.value)
        is NotesAction.Content -> changeContent(action.value)
        else -> dispatchCommand(action)
    }
}

private fun NotesViewModel.dispatchCommand(action: NotesAction) {
    when (action) {
        NotesAction.Refresh -> refresh()
        NotesAction.CancelRead -> cancelRead()
        NotesAction.Save -> save()
        NotesAction.AskDelete -> askDelete()
        NotesAction.Delete -> confirmDelete()
        NotesAction.Restore -> restore()
        else -> dispatchNavigationCommand(action)
    }
}

private fun NotesViewModel.dispatchNavigationCommand(action: NotesAction) {
    when (action) {
        NotesAction.Back -> onBackRequested()
        NotesAction.Discard -> discardAndContinue()
        NotesAction.DismissDialog -> dismissDialog()
        NotesAction.AskReload -> askReloadConflict()
        NotesAction.Reload -> reloadConflict()
        else -> Unit
    }
}
