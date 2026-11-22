package com.deepstudent.features.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepstudent.design.DstuBrandMark

@Composable
internal fun NotesTopBar(state: NotesUiState, wide: Boolean, onAction: (NotesAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!wide && state.showsEditor) {
            IconButton(onClick = { onAction(NotesAction.Back) }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回笔记列表")
            }
        } else {
            DstuBrandMark("Deep Student", Modifier.padding(start = 8.dp, end = 4.dp).size(24.dp))
        }
        Text(
            if (state.collection == NotesCollection.Trash) "回收站" else "笔记",
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TopActions(state, onAction)
    }
}

@Composable
private fun TopActions(state: NotesUiState, onAction: (NotesAction) -> Unit) {
    val draft = state.draft
    val enabled = state.operation == null && !state.writeUncertain
    if (draft == null) {
        return
    } else if (draft.isDeleted) {
        IconButton(onClick = { onAction(NotesAction.Restore) }, enabled = enabled) {
            Icon(Icons.Outlined.RestoreFromTrash, "恢复笔记")
        }
    } else {
        if (draft.original != null) {
            IconButton(onClick = { onAction(NotesAction.AskDelete) }, enabled = enabled) {
                Icon(Icons.Outlined.DeleteOutline, "删除笔记")
            }
        }
        IconButton(
            onClick = { onAction(NotesAction.Save) },
            enabled = enabled && !state.conflict && (draft.original == null || draft.isDirty),
        ) { Icon(Icons.Outlined.Save, "保存笔记") }
    }
}
