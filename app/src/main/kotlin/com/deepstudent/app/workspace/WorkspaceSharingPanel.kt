package com.deepstudent.app.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.deepstudent.app.storage.documents.WorkspaceDocumentPrivacy
import com.deepstudent.app.storage.documents.WorkspaceSharingRow
import com.deepstudent.features.workspace.api.PanelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val WorkspaceSharingPanelKey = PanelKey.parse("file-sharing")

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun WorkspaceSharingPanel(privacy: WorkspaceDocumentPrivacy) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(emptyList<WorkspaceSharingRow>()) }
    var selected by remember { mutableStateOf<WorkspaceSharingRow?>(null) }
    var status by remember { mutableStateOf("正在读取文件…") }
    suspend fun refresh() {
        try {
            rows = privacy.list()
            status = if (rows.isEmpty()) "暂无工作区文件或附件" else "撤回后，外部应用不能继续通过系统文件入口访问。文件内容保留。"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = "读取文件失败，请刷新重试。"
        }
    }
    LaunchedEffect(privacy) { refresh() }
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp).testTag("workspace-sharing-panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(status)
            TextButton(onClick = { scope.launch { refresh() } }) { Text("刷新") }
        }
        items(rows, key = { it.file.id }) { row ->
            WorkspaceSharingEntry(row) { selected = row }
        }
    }
    if (selected != null) {
        WorkspaceSharingConfirm(onCancel = { selected = null }, onConfirm = {
            val row = selected ?: return@WorkspaceSharingConfirm
            selected = null
            scope.launch {
                try {
                    privacy.makePrivate(row.file)
                    refresh()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status = "撤回失败，请刷新后重试。"
                }
            }
        })
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun WorkspaceSharingEntry(row: WorkspaceSharingRow, select: () -> Unit) {
    Column {
        Text(row.file.name)
        if (row.isPrivate) {
            Text("已撤回外部访问")
        } else {
            Button(onClick = select) { Text("撤回外部访问") }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun WorkspaceSharingConfirm(onCancel: () -> Unit, onConfirm: () -> Unit) = AlertDialog(
    onDismissRequest = onCancel,
    title = { Text("将文件设为私有？") },
    text = { Text("外部应用将不能重新打开此文件，未提交的写入会被拒绝。外部应用已经读取的内容无法收回。若需再次分享，请另存一份。") },
    confirmButton = { TextButton(onConfirm, Modifier.testTag("workspace-sharing-confirm")) { Text("撤回访问") } },
    dismissButton = { TextButton(onCancel) { Text("取消") } },
)
