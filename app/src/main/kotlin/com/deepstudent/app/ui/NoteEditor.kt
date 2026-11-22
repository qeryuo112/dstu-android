package com.deepstudent.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.deepstudent.features.editor.backend.application.editing.DefaultEditorSessionFactory
import com.deepstudent.features.editor.ui.EditorContent
import com.deepstudent.features.editor.ui.rememberEditorController
import com.deepstudent.features.notes.ui.NoteDraft

@Composable
internal fun NoteEditor(draft: NoteDraft, enabled: Boolean, onTitle: (String) -> Unit, onContent: (String) -> Unit) {
    val session = remember(draft.editorKey) { DefaultEditorSessionFactory().create(draft.content) }
    val controller = rememberEditorController(session, onContent)
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        BasicTextField(
            value = draft.title, onValueChange = onTitle, enabled = enabled && !draft.isDeleted,
            singleLine = false,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("note-title")
                .semantics { contentDescription = "笔记标题" },
            textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onBackground),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
        EditorContent(controller, Modifier.fillMaxWidth().weight(1f), enabled && !draft.isDeleted)
    }
}
