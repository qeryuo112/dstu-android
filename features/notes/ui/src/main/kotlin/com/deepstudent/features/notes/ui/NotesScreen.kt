package com.deepstudent.features.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun NotesScreen(
    state: NotesUiState,
    onAction: (NotesAction) -> Unit,
    editorContent: NotesEditorSlot = ::BasicNotesEditor,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
        NotesLayout(state, onAction, editorContent)
    }
}

@Composable
private fun NotesLayout(state: NotesUiState, onAction: (NotesAction) -> Unit, editorContent: NotesEditorSlot) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("notes-screen")) {
        val wide = maxWidth >= 720.dp
        Column(Modifier.fillMaxSize()) {
            NotesTopBar(state, wide, onAction)
            HorizontalDivider()
            NotesBody(state, wide, onAction, editorContent)
        }
        NotesDialogs(state, onAction)
    }
}

@Composable
private fun NotesBody(
    state: NotesUiState,
    wide: Boolean,
    onAction: (NotesAction) -> Unit,
    editor: NotesEditorSlot,
) {
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NotesList(state, onAction, Modifier.width(300.dp).fillMaxSize())
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxSize()) { NotesDetail(state, onAction, editor) }
        }
    } else if (state.showsEditor) {
        NotesDetail(state, onAction, editor)
    } else {
        NotesList(state, onAction, Modifier.fillMaxSize())
    }
}
