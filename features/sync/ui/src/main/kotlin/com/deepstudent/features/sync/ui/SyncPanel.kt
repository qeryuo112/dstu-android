package com.deepstudent.features.sync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncTransferPhase
import com.deepstudent.services.sync.api.SyncTransferState

@Composable
fun SyncPanel(controller: SyncController) {
    val state by controller.state.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("同步配置")
        when (val current = state) {
            SyncUiState.Loading -> CircularProgressIndicator(Modifier.testTag("sync-loading"))
            SyncUiState.Pairing -> {
                Text("正在配对")
                TextButton(onClick = controller::cancel, modifier = Modifier.testTag("sync-cancel")) { Text("取消") }
            }
            is SyncUiState.Connected -> ConnectedPanel(current.configuration, controller)
            is SyncUiState.KeyRecovery -> LostKeyPanel(current, controller)
            is SyncUiState.Error -> {
                Text(errorMessage(current.error), Modifier.testTag("sync-error"))
                TextButton(onClick = controller::reload, modifier = Modifier.testTag("sync-retry")) { Text("重试读取配置") }
                PairingForm(controller)
            }
            SyncUiState.Unconfigured -> PairingForm(controller)
        }
    }
}

@Composable
private fun LostKeyPanel(state: SyncUiState.KeyRecovery, controller: SyncController) {
    var confirm by remember { mutableStateOf(false) }
    Text(errorMessage(SyncError.CREDENTIAL_KEY_LOST), Modifier.testTag("sync-key-lost"))
    state.resetError?.let { Text("未能重置密钥：${errorMessage(it)}", Modifier.testTag("sync-key-reset-error")) }
    TextButton(onClick = controller::reload, modifier = Modifier.testTag("sync-key-recheck")) { Text("重新检查") }
    Button(onClick = { confirm = true }, modifier = Modifier.testTag("sync-key-reset")) { Text("重置密钥并重新配对") }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("重置本机凭据密钥？") },
            text = { Text("将清除本机保存的凭据和同步配置，需重新输入密码。设备 ID、笔记和待同步更改不会删除，也不会修改远端仓库。") },
            confirmButton = {
                TextButton(onClick = { confirm = false; controller.resetCredentialsAfterKeyLoss() },
                    modifier = Modifier.testTag("sync-key-reset-confirm")) { Text("重置密钥") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }, modifier = Modifier.testTag("sync-key-reset-dismiss")) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ConnectedPanel(configuration: SyncConfiguration, controller: SyncController) {
    Text("已保存同步配置", Modifier.testTag("sync-connected"))
    Text("设备 ID：${configuration.deviceId}", Modifier.testTag("sync-device-id"))
    if (configuration.encrypted) Text("端到端加密已启用", Modifier.testTag("sync-encrypted"))
    TransferActions(controller)
    val running by controller.running.collectAsState()
    if (running) return
    if (configuration.encrypted && controller.transferAvailable) EncryptionRetry(controller)
    ClearConfigurationButton(controller)
}

@Composable
private fun EncryptionRetry(controller: SyncController) {
    var editing by remember { mutableStateOf(false) }
    if (!editing) {
        TextButton(onClick = { editing = true }, modifier = Modifier.testTag("sync-encryption-change")) { Text("更正本机加密密码") }
        return
    }
    var password by remember { mutableStateOf("") }
    PasswordField(password, { password = it }, "sync-encryption-replacement", "仓库加密密码")
    Button(onClick = {
        val secret = password.toCharArray()
        password = ""
        editing = false
        controller.replaceEncryptionPasswordAndRetry(secret)
    }, enabled = password.isNotEmpty(), modifier = Modifier.testTag("sync-encryption-save")) { Text("保存并重试同步") }
    TextButton(onClick = { password = ""; editing = false }) { Text("取消") }
}

@Composable
private fun TransferActions(controller: SyncController) {
    if (!controller.transferAvailable) return
    val state by controller.transfer.collectAsState()
    val running by controller.running.collectAsState()
    Text(transferMessage(state, running), Modifier.testTag("sync-transfer-state"))
    if (running) {
        TextButton(onClick = controller::cancel, modifier = Modifier.testTag("sync-transfer-cancel")) { Text("取消同步") }
    } else {
        Button(onClick = controller::synchronize, modifier = Modifier.testTag("sync-transfer-start")) {
            Text(if (state.phase == SyncTransferPhase.FAILED) "重试同步" else "同步")
        }
    }
}

private fun transferMessage(state: SyncTransferState, running: Boolean): String = when (state.phase) {
    SyncTransferPhase.IDLE -> if (running) "正在准备同步" else "尚未同步"
    SyncTransferPhase.BLOBS -> "正在同步资源"
    SyncTransferPhase.DOWNLOAD -> "正在下载更改"
    SyncTransferPhase.UPLOAD -> "正在上传更改"
    SyncTransferPhase.COMPLETE -> "同步完成"
    SyncTransferPhase.FAILED -> "同步失败：${state.error?.let(::errorMessage) ?: "存储错误"}"
}

@Composable
private fun ClearConfigurationButton(controller: SyncController) {
    var confirm by remember { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }, modifier = Modifier.testTag("sync-clear")) { Text("清除配置并重新配对") }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("清除同步配置？") },
            text = { Text("将删除本机同步配置和保存的凭据，保留设备 ID 和本地数据。") },
            confirmButton = {
                TextButton(onClick = { confirm = false; controller.clear() }, modifier = Modifier.testTag("sync-clear-confirm")) {
                    Text("清除配置")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }, modifier = Modifier.testTag("sync-clear-dismiss")) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PairingForm(controller: SyncController) {
    var endpoint by remember { mutableStateOf("") }
    var root by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var encryptionPassword by remember { mutableStateOf("") }
    OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth().testTag("sync-endpoint"), label = { Text("服务器地址") })
    OutlinedTextField(root, { root = it }, Modifier.fillMaxWidth().testTag("sync-root"), label = { Text("仓库路径") })
    OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth().testTag("sync-username"), label = { Text("用户名") })
    PasswordField(password, { password = it }, "sync-password", "WebDAV 密码")
    PasswordField(encryptionPassword, { encryptionPassword = it }, "sync-encryption-password", "仓库加密密码（可选）")
    fun pair(mode: PairingMode) {
        val request = PairingRequest(endpoint, username, root, password.toCharArray(),
            encryptionPassword.takeIf { it.isNotEmpty() }?.toCharArray())
        password = ""
        encryptionPassword = ""
        controller.pair(mode, request)
    }
    Button(onClick = { pair(PairingMode.CREATE) }, modifier = Modifier.testTag("sync-create")) { Text("创建仓库并配对") }
    Button(onClick = { pair(PairingMode.JOIN) }, modifier = Modifier.testTag("sync-join")) { Text("加入仓库并配对") }
}

@Composable
private fun PasswordField(value: String, change: (String) -> Unit, tag: String, label: String) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth().testTag(tag), label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation())
}

private val errorMessages = mapOf(
    SyncError.INVALID_CONFIGURATION to "配置无效",
    SyncError.INSECURE_ENDPOINT to "服务器地址不安全",
    SyncError.AUTHENTICATION to "认证失败",
    SyncError.NOT_FOUND to "仓库不存在",
    SyncError.ALREADY_EXISTS to "仓库已存在",
    SyncError.INCOMPATIBLE_FORMAT to "仓库格式不兼容",
    SyncError.ENCRYPTED_REPOSITORY to "该仓库需要桌面端使用的加密密码",
    SyncError.ENCRYPTION_PASSWORD_INCORRECT to "仓库加密密码不正确，待上传更改已保留",
    SyncError.INVALID_ENCRYPTION_MARKER to "仓库加密标记无效或不受支持",
    SyncError.ENCRYPTION_PROOF_REQUIRED to "无法用仓库内的旧备份或同步数据验证密码，未升级标记",
    SyncError.ENCRYPTION_CLAIM_CONFLICT to "仓库加密设置正在变更，请稍后重新配对",
    SyncError.ENCRYPTION_UPGRADE_UNCERTAIN to "未确认旧加密标记的升级结果。请重新配对；若仍被占用，请先核对仓库中的升级记录",
    SyncError.ENCRYPTION_MODE_MISMATCH to "加密仓库包含未经加密的数据，已停止同步",
    SyncError.ENCRYPTED_DATA_INVALID to "加密数据校验失败，未应用该对象",
    SyncError.INVALID_REPOSITORY to "仓库无效",
    SyncError.NETWORK to "网络错误",
    SyncError.STORAGE to "配置存储失败",
    SyncError.CREDENTIAL_UNAVAILABLE to "凭据不可用，请重新配对",
    SyncError.CREDENTIAL_INTEGRITY to "凭据校验失败，请重新配对",
    SyncError.CREDENTIAL_KEY_LOST to "本机凭据密钥已失效，请重置密钥后重新配对",
    SyncError.LIMIT_EXCEEDED to "超出限制",
    SyncError.CANCELLED to "已取消",
)

private fun errorMessage(error: SyncError): String = errorMessages.getValue(error)
