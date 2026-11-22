package com.deepstudent.features.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

typealias NotesEditorSlot = @Composable (NoteDraft, Boolean, (String) -> Unit, (String) -> Unit) -> Unit

@Composable
fun BasicNotesEditor(draft: NoteDraft, enabled: Boolean, onTitle: (String) -> Unit, onContent: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 800.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp)) {
            BasicTextField(
                value = draft.title, onValueChange = onTitle, enabled = enabled && !draft.isDeleted,
                modifier = Modifier.fillMaxWidth().testTag("note-title").semantics { contentDescription = "笔记标题" },
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), singleLine = true,
            )
            if (draft.tags.isNotEmpty()) {
                Text(draft.tags.joinToString("  ·  "), Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.Top) {
                BasicTextField(
                    value = draft.content, onValueChange = onContent, enabled = enabled && !draft.isDeleted,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).testTag("note-content")
                        .semantics { contentDescription = "笔记正文" },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        if (draft.content.isEmpty()) Text("开始记录...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    },
                )
            }
        }
    }
}
