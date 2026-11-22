package com.deepstudent.features.notes.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun NotesDialogs(state: NotesUiState, onAction: (NotesAction) -> Unit) {
    when (state.dialog) {
        is NotesDialog.Discard -> ConfirmationDialog("放弃未保存的修改？", "当前修改不会保存。", "放弃修改", NotesAction.Discard, onAction)
        NotesDialog.Delete -> ConfirmationDialog(
            "删除笔记？", if (state.draft?.isDirty == true) "未保存的修改将丢失，笔记会移至回收站。" else "笔记将移至回收站。",
            "删除", NotesAction.Delete, onAction,
        )
        NotesDialog.ReloadConflict -> ConfirmationDialog("重新加载笔记？", "本地未保存的修改将丢失。", "重新加载", NotesAction.Reload, onAction)
        null -> Unit
    }
}

@Composable
private fun ConfirmationDialog(title: String, message: String, confirm: String, action: NotesAction, onAction: (NotesAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(NotesAction.DismissDialog) },
        title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { onAction(action) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = { onAction(NotesAction.DismissDialog) }) { Text("取消") } },
    )
}
