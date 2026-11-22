package com.deepstudent.features.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepstudent.features.notes.api.NoteDocument

@Composable
internal fun NotesList(state: NotesUiState, onAction: (NotesAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).testTag("notes-list")) {
        CollectionTabs(state, onAction)
        when (state.listStatus) {
            NotesListStatus.Loading -> ListMessage("正在加载笔记", onAction, loading = true)
            NotesListStatus.Error -> ListMessage("加载失败", onAction)
            NotesListStatus.Cancelled -> ListMessage("加载已取消", onAction)
            NotesListStatus.Ready -> NotesListContent(state, onAction)
        }
    }
}

@Composable
private fun CollectionTabs(state: NotesUiState, onAction: (NotesAction) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        NotesCollection.entries.forEach { collection ->
            val label = if (collection == NotesCollection.Active) "笔记" else "回收站"
            Text(
                label,
                Modifier.selectable(
                    selected = state.collection == collection, enabled = state.operation == null, role = Role.Tab,
                    onClick = { onAction(NotesAction.Navigate(NotesDestination.Collection(collection))) },
                ).padding(horizontal = 12.dp, vertical = 16.dp),
                color = if (state.collection == collection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { onAction(NotesAction.Navigate(NotesDestination.Create)) },
            enabled = state.operation == null,
        ) { Icon(Icons.Outlined.Add, "新建笔记") }
        IconButton(onClick = { onAction(NotesAction.Refresh) }, enabled = state.listStatus != NotesListStatus.Loading) {
            Icon(Icons.Outlined.Refresh, "刷新列表")
        }
    }
    HorizontalDivider()
}

@Composable
private fun NotesListContent(state: NotesUiState, onAction: (NotesAction) -> Unit) {
    val notes = state.visibleNotes
    if (notes.isEmpty()) {
        ListMessage(if (state.collection == NotesCollection.Active) "暂无笔记" else "回收站为空", onAction, empty = true)
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(notes, key = { it.id }) { note ->
                NoteListRow(note, state.draft?.original?.id == note.id) {
                    onAction(NotesAction.Navigate(NotesDestination.Open(note.id)))
                }
            }
        }
    }
}

@Composable
private fun NoteListRow(note: NoteDocument, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    Row(
        Modifier.fillMaxWidth().background(background).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("note-row-${note.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Description, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(note.updatedAt, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ListMessage(message: String, onAction: (NotesAction) -> Unit, loading: Boolean = false, empty: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(message, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) TextButton(onClick = { onAction(NotesAction.CancelRead) }) { Text("取消") }
        else if (!empty) TextButton(onClick = { onAction(NotesAction.Refresh) }) { Text("重试") }
    }
}
