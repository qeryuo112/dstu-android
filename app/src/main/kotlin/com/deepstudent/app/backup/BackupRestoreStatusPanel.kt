package com.deepstudent.app.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun BackupRestoreStatusPanel(
    status: BackupRestoreUiStatus,
    canSchedule: Boolean,
    legacyImport: Boolean = false,
    onConfirm: () -> Unit,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val available = canSchedule && status !in Waiting
    Column(Modifier.padding(horizontal = 12.dp).testTag("backup-restore-status")) {
        status.message?.let { Text(it) }
        if (available) Button(onClick = { confirm = true }) { Text(if (legacyImport) "安排导入" else "安排恢复") }
    }
    if (confirm && available) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (legacyImport) "确认导入旧移动备份？" else "确认恢复完整快照？") },
            text = {
                val content = if (legacyImport) {
                    "下次冷启动时校验四库及资源引用。只有校验通过才切换；当前数据槽会保留，不接管同步身份。"
                } else {
                    "下次冷启动时，备份会成为活动数据。当前数据槽会保留；请先保存正在编辑的内容。"
                }
                Text("$content 如果本机此前已经配对，恢复后需用新设备身份重新配对。")
            },
            confirmButton = { TextButton(onClick = { confirm = false; onConfirm() }) { Text("确认安排") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

private val Waiting = setOf(BackupRestoreUiStatus.QUEUING, BackupRestoreUiStatus.QUEUED,
    BackupRestoreUiStatus.APPLYING, BackupRestoreUiStatus.NEEDS_REVIEW)
