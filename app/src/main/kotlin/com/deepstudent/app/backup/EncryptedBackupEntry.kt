package com.deepstudent.app.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.deepstudent.services.backup.api.DomainAction

@Composable
internal fun EncryptedBackupEntry(model: EncryptedBackupModel, restoreStatus: BackupRestoreUiStatus) {
    val state by model.state.collectAsState()
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { source -> model.select(source, EncryptedBackupIntent.IMPORT) }
    }
    val pickExport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { source -> model.select(source, EncryptedBackupIntent.EXPORT) }
    }
    val pickPortable = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { source -> model.select(source, EncryptedBackupIntent.EXPORT_PORTABLE) }
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(model::save) }
    val saveDsbk = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let(model::save) }
    EncryptedBackupScreen(state, restoreStatus, EncryptedBackupScreenActions(
        pick = { intent ->
            val mimeTypes = arrayOf("application/zip", "application/octet-stream")
            when (intent) {
                EncryptedBackupIntent.IMPORT -> pickImport.launch(mimeTypes)
                EncryptedBackupIntent.EXPORT -> pickExport.launch(mimeTypes)
                EncryptedBackupIntent.EXPORT_PORTABLE -> pickPortable.launch(mimeTypes)
            }
        },
        save = { choice ->
            if (choice.mime == "application/zip") saveZip.launch(choice.name) else saveDsbk.launch(choice.name)
        },
        cancel = model::cancel,
        authenticate = model::authenticate,
        retry = model::retry,
        confirmRestore = model::confirmRestore,
    ))
}

/** Only callbacks cross this boundary: the real route owns SAF and the model owns all operations. */
internal class EncryptedBackupScreenActions(
    val pick: (EncryptedBackupIntent) -> Unit,
    val save: (EncryptedBackupState.ReadyToSave) -> Unit,
    val cancel: () -> Unit,
    val authenticate: (CharArray) -> Unit,
    val retry: () -> Unit,
    val confirmRestore: () -> Unit,
)

@Composable
internal fun EncryptedBackupScreen(
    state: EncryptedBackupState,
    restoreStatus: BackupRestoreUiStatus,
    actions: EncryptedBackupScreenActions,
) {
    // Password and confirmation dialogs handle their own back/dismiss; other states return to the entry.
    BackHandler(enabled = state != EncryptedBackupState.Idle && state !is EncryptedBackupState.Password) { actions.cancel() }
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val working = state is EncryptedBackupState.Working
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { actions.pick(EncryptedBackupIntent.IMPORT) }, enabled = !working,
                modifier = Modifier.testTag("encrypted-pick")) { Text("打开加密备份") }
            TextButton(onClick = { actions.pick(EncryptedBackupIntent.EXPORT) }, enabled = !working,
                modifier = Modifier.testTag("encrypted-export-pick")) { Text("整包加密") }
        }
        TextButton(onClick = { actions.pick(EncryptedBackupIntent.EXPORT_PORTABLE) }, enabled = !working,
            modifier = Modifier.testTag("encrypted-portable-export-pick")) { Text("生成桌面兼容 ZIP") }
        EncryptedBackupStateContent(state, restoreStatus, actions)
    }
}

@Composable
private fun EncryptedBackupStateContent(
    state: EncryptedBackupState, restoreStatus: BackupRestoreUiStatus, actions: EncryptedBackupScreenActions,
) {
    when (state) {
        EncryptedBackupState.Idle -> EncryptedBackupIntroduction()
        is EncryptedBackupState.Password -> BackupPasswordDialog(state.intent, actions.cancel, actions.authenticate)
        EncryptedBackupState.Working -> EncryptedBackupWorking(actions.cancel)
        is EncryptedBackupState.Report -> EncryptedBackupReport(state, restoreStatus, actions.confirmRestore)
        is EncryptedBackupState.ReadyToSave -> EncryptedBackupSave(state, actions.save, actions.cancel)
        is EncryptedBackupState.Done -> EncryptedBackupResult(state, restoreStatus)
        is EncryptedBackupState.Failed -> EncryptedBackupError(state, actions.retry, actions.cancel)
    }
}

@Composable
private fun EncryptedBackupIntroduction() {
    Text("验证备份密码后可查看能恢复和保留在原备份的数据。")
    Text("整包加密：加密整个备份 ZIP，打开时需要密码。")
    Text("桌面兼容 ZIP：仅加密敏感子包，其他内容仍是普通 ZIP。")
}

@Composable
private fun EncryptedBackupWorking(cancel: () -> Unit) {
    Text("正在处理备份…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    LinearProgressIndicator(Modifier.semantics { contentDescription = "正在处理加密备份" })
    TextButton(onClick = cancel, modifier = Modifier.testTag("encrypted-cancel-working")) { Text("取消") }
}

@Composable
private fun EncryptedBackupSave(
    state: EncryptedBackupState.ReadyToSave, save: (EncryptedBackupState.ReadyToSave) -> Unit, cancel: () -> Unit,
) {
    Text("已生成 ${state.name}。选择保存位置完成导出；原备份不会修改。", Modifier.testTag("encrypted-save-description"))
    Button(onClick = { save(state) }, modifier = Modifier.testTag("encrypted-save")) { Text("选择保存位置") }
    TextButton(onClick = cancel) { Text("放弃导出") }
}

@Composable
private fun EncryptedBackupResult(state: EncryptedBackupState.Done, status: BackupRestoreUiStatus) {
    Text(state.message, Modifier.testTag("encrypted-result").semantics { liveRegion = LiveRegionMode.Polite })
    status.message?.let { Text(it) }
}

@Composable
private fun EncryptedBackupError(state: EncryptedBackupState.Failed, retry: () -> Unit, cancel: () -> Unit) {
    Text(state.message, Modifier.testTag("encrypted-error").semantics { liveRegion = LiveRegionMode.Polite })
    if (state.canRetry) TextButton(onClick = retry, modifier = Modifier.testTag("encrypted-retry")) { Text("重试输入密码") }
    TextButton(onClick = cancel) { Text("返回") }
}

@Composable
private fun EncryptedBackupReport(state: EncryptedBackupState.Report, status: BackupRestoreUiStatus, confirm: () -> Unit) {
    val plan = state.plan
    BackupRestoreStatusPanel(status, plan.canStage, plan.legacyImport != null, confirm)
    if (plan.domains.any { it.action == DomainAction.RETAIN_IN_SOURCE }) {
        Text("只恢复 Android 支持的数据。下方标注的内容仍在原备份，不会导入或启用。", Modifier.testTag("encrypted-retained"))
    }
    BackupPreviewScreen(BackupPreviewState.Report(plan), {}, {}, showPicker = false)
}

@Composable
private fun BackupPasswordDialog(intent: EncryptedBackupIntent, cancel: () -> Unit, submit: (CharArray) -> Unit) {
    // Deliberately not saveable: a configuration change must not persist the password.
    var input by remember { mutableStateOf("") }
    val exporting = intent != EncryptedBackupIntent.IMPORT
    AlertDialog(
        onDismissRequest = { input = ""; cancel() },
        title = { Text(if (exporting) "设置备份密码" else "输入备份密码") },
        text = {
            Column {
                if (intent == EncryptedBackupIntent.EXPORT_PORTABLE) Text("按桌面格式，只对敏感子包加密。其他内容仍是普通 ZIP。")
                if (exporting) Text("请妥善保存密码；忘记密码将无法打开加密内容。")
                OutlinedTextField(input, { input = it }, label = { Text("备份密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.testTag("encrypted-password"))
            }
        },
        confirmButton = {
            TextButton(enabled = input.isNotEmpty(), onClick = {
                val password = input.toCharArray()
                input = ""
                submit(password)
            }, modifier = Modifier.testTag("encrypted-password-submit")) { Text(if (exporting) "开始加密" else "验证并打开") }
        },
        dismissButton = { TextButton(onClick = { input = ""; cancel() }) { Text("取消") } },
    )
}
