package com.deepstudent.app.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun ContinuityRestoreScreen(model: ContinuityRestoreModel, status: BackupRestoreUiStatus) {
    val view by model.state.collectAsState()
    var selected by rememberSaveable { mutableStateOf<ContinuityDocument?>(null) }
    var confirmRenew by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val kind = selected
        selected = null
        if (kind != null && uri != null) model.select(kind, uri)
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("连续迁移 · 保留旧同步身份")
        Text("先使用新凭据配对到原仓库。本方式需要仓库管理员签发授权，确认旧设备已失去写入权限。普通恢复无需这些材料。")
        status.message?.let { Text(it) }
        Button(onClick = model::issue, enabled = !view.busy, modifier = Modifier.testTag("continuity-issue")) { Text("查看本机接管申请") }
        val target = view.target
        if (target != null) {
            TextButton(onClick = { confirmRenew = true }, enabled = !view.busy) { Text("生成新申请") }
            ContinuityInputPanel(target, view, model) { kind ->
                selected = kind
                picker.launch(arrayOf("*/*"))
            }
        }
        if (view.busy) {
            LinearProgressIndicator()
            TextButton(onClick = model::cancel) { Text("取消检查") }
        }
        view.message?.let { Text(it, Modifier.testTag("continuity-result")) }
        view.review?.let { ContinuityConfirm(it, model::schedule) }
    }
    if (confirmRenew) RenewalConfirmation({ confirmRenew = false }, { confirmRenew = false; model.renew() })
}

@Composable
private fun RenewalConfirmation(cancel: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = cancel, title = { Text("替换当前申请？") },
        text = { Text("原申请的授权文件将不再匹配。如旧申请已向仓库发起接管，请先联系管理员；不要为重试而换申请。") },
        confirmButton = { TextButton(onClick = confirm) { Text("生成新申请") } },
        dismissButton = { TextButton(onClick = cancel) { Text("取消") } })
}

@Composable
private fun ContinuityInputPanel(
    target: ContinuityTarget, state: ContinuityScreenState, model: ContinuityRestoreModel, choose: (ContinuityDocument) -> Unit,
) {
    SelectionContainer {
        Text("请把下面两项交给仓库管理员：\n安装 ID：${target.installationId}\n申请 ID：${target.operationId}",
            Modifier.testTag("continuity-target"))
    }
    for (kind in ContinuityDocument.entries) {
        val filled = if (kind in state.selected) "（已选）" else ""
        TextButton(onClick = { choose(kind) }, enabled = !state.busy) { Text("选择${kind.label}$filled") }
    }
    OutlinedTextField(state.fingerprint, model::setPin, enabled = !state.busy, singleLine = true,
        label = { Text("管理员通过另一个可信渠道提供的公钥指纹") },
        modifier = Modifier.testTag("continuity-fingerprint"))
    Button(onClick = model::review, enabled = !state.busy && state.selected.size == ContinuityDocument.entries.size,
        modifier = Modifier.testTag("continuity-review")) { Text("核对文件和授权") }
}

@Composable
private fun ContinuityConfirm(review: ContinuityReview, schedule: () -> Unit) {
    var confirm by remember(review) { mutableStateOf(false) }
    Text("文件和签名已核对，快照中的旧写者上传序列：${review.lastSequence}。尚未占用仓库写入资格。",
        Modifier.testTag("continuity-reviewed"))
    Button(onClick = { confirm = true }) { Text("安排连续迁移") }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("确认保留旧同步身份？") },
        text = { Text("下次冷启动会复查仓库和旧写者序列。全部通过才切换；旧数据槽仍保留。不通过不会改为普通恢复。") },
        confirmButton = { TextButton(onClick = { confirm = false; schedule() }) { Text("确认安排") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
    )
}
