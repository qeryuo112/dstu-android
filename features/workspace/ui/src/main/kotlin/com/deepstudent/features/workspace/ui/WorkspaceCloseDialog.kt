package com.deepstudent.features.workspace.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.deepstudent.features.workspace.api.CloseDecision
import com.deepstudent.features.workspace.api.WorkspaceState

@Composable
internal fun WorkspaceCloseDialog(state: WorkspaceState, dispatch: (WorkspaceAction) -> Unit) {
    if (state.pendingClose == null) return
    val pending = state.pendingClose ?: return
    val uncertain = state.layout.panel(pending.panelId)?.saveUncertain == true
    val decide: (CloseDecision) -> Unit = { dispatch(WorkspaceAction.Decide(it)) }
    AlertDialog(
        onDismissRequest = { if (state.canClose) decide(CloseDecision.Cancel) },
        title = { Text("未保存的修改") },
        text = { Text(if (uncertain) "上次保存结果未确认。请先核对笔记列表，避免重复创建。" else "保存修改后关闭？") },
        confirmButton = {
            TextButton({ decide(CloseDecision.Save) }, enabled = state.canClose && !uncertain,
                modifier = Modifier.testTag("workspace-close-save")) { Text("保存并关闭") }
        },
        dismissButton = {
            TextButton({ decide(CloseDecision.Cancel) }, enabled = state.canClose,
                modifier = Modifier.testTag("workspace-close-cancel")) { Text("取消") }
            TextButton({ decide(CloseDecision.Discard) }, enabled = state.canClose,
                modifier = Modifier.testTag("workspace-close-discard")) { Text("放弃修改") }
            if (uncertain) TextButton({ dispatch(WorkspaceAction.AcknowledgeSave(pending.panelId)) }) { Text("已核对，允许再次保存") }
        },
    )
}
