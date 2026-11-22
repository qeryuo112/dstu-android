package com.deepstudent.features.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun NotesDetail(state: NotesUiState, onAction: (NotesAction) -> Unit, editor: NotesEditorSlot) {
    val draft = state.draft
    when {
        state.operation == NotesOperation.Open && draft == null -> DetailLoading(onAction)
        draft != null -> DetailContent(state, draft, onAction, editor)
        else -> DetailPlaceholder(state, onAction)
    }
}

@Composable
private fun DetailContent(state: NotesUiState, draft: NoteDraft, onAction: (NotesAction) -> Unit, editor: NotesEditorSlot) {
    Column(Modifier.fillMaxSize()) {
        DetailStatus(state, onAction)
        editor(draft, state.operation == null && !draft.isDeleted, { onAction(NotesAction.Title(it)) }, { onAction(NotesAction.Content(it)) })
    }
}

@Composable
private fun DetailStatus(state: NotesUiState, onAction: (NotesAction) -> Unit) {
    val operation = state.operation
    val message = state.message
    if (operation != null) {
        Text(operation.label(), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (operation == NotesOperation.Reload) TextButton(onClick = { onAction(NotesAction.CancelRead) }) { Text("取消读取") }
    } else if (message != null) {
        Text(message, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
        if (state.conflict) TextButton(onClick = { onAction(NotesAction.AskReload) }) { Text("重新加载") }
    } else {
        val label = if (state.draft?.isDirty == true || state.draft?.original == null) "未保存" else "已保存"
        Text(label, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun NotesOperation.label(): String = when (this) {
    NotesOperation.Open, NotesOperation.Reload -> "正在读取"
    NotesOperation.Save -> "正在保存"
    NotesOperation.Delete -> "正在删除"
    NotesOperation.Restore -> "正在恢复"
}

@Composable
private fun DetailLoading(onAction: (NotesAction) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        Text("正在打开笔记", Modifier.padding(12.dp))
        TextButton(onClick = { onAction(NotesAction.CancelRead) }) { Text("取消") }
    }
}

@Composable
private fun DetailPlaceholder(state: NotesUiState, onAction: (NotesAction) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.message ?: "选择一篇笔记", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val openingId = state.openingId
            if (state.message != null && openingId != null) {
                TextButton(onClick = { onAction(NotesAction.Navigate(NotesDestination.Open(openingId))) }) { Text("重试") }
            }
        }
    }
}
