package com.deepstudent.features.execution.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepstudent.services.execution.api.ExecutionCommand
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionReceipt
import com.deepstudent.services.execution.api.ExecutionResult
import com.deepstudent.services.execution.api.ExecutionSystem
import com.deepstudent.services.execution.api.ShizukuConnectionState
import com.deepstudent.services.execution.api.ShizukuIdentity
import com.deepstudent.services.execution.api.ShizukuStatus

@Composable
fun ShizukuExecutionRoute(
    system: ExecutionSystem,
    modifier: Modifier = Modifier,
) {
    val vm: ShizukuExecutionViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ShizukuExecutionViewModel(system) as T
        },
    )
    val state by vm.uiState.collectAsState()
    ShizukuExecutionScreen(
        state = state,
        onRefresh = vm::refresh,
        onRequestPermission = vm::requestPermission,
        onVerifyIdentity = vm::verifyIdentity,
        onListPackages = vm::listPackages,
        onCancel = vm::cancelExecution,
        modifier = modifier,
    )
}

@Composable
fun ShizukuExecutionScreen(
    state: ShizukuUiState,
    onRefresh: () -> Unit,
    onRequestPermission: () -> Unit,
    onVerifyIdentity: () -> Unit,
    onListPackages: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = state.status
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Shizuku 执行",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onRefresh,
                enabled = state.runningCommand == null,
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新状态")
            }
        }
        Spacer(Modifier.height(10.dp))
        StatusBand(status)
        Spacer(Modifier.height(14.dp))
        IdentityRows(status)
        Spacer(Modifier.height(14.dp))
        ActionRow(
            state = state,
            onRequestPermission = onRequestPermission,
            onVerifyIdentity = onVerifyIdentity,
            onListPackages = onListPackages,
            onCancel = onCancel,
        )
        Spacer(Modifier.height(18.dp))
        HorizontalDivider()
        Spacer(Modifier.height(14.dp))
        ResultPanel(state.result)
    }
}

@Composable
private fun StatusBand(status: ShizukuStatus) {
    val container = when (status.state) {
        ShizukuConnectionState.READY -> MaterialTheme.colorScheme.secondaryContainer
        ShizukuConnectionState.PERMISSION_REQUIRED,
        ShizukuConnectionState.REVOKED,
        ShizukuConnectionState.BINDER_DEAD,
        ShizukuConnectionState.PROTOCOL_ERROR,
        ShizukuConnectionState.UNTRUSTED_BACKEND -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when (status.state) {
        ShizukuConnectionState.READY -> MaterialTheme.colorScheme.onSecondaryContainer
        ShizukuConnectionState.PERMISSION_REQUIRED,
        ShizukuConnectionState.REVOKED,
        ShizukuConnectionState.BINDER_DEAD,
        ShizukuConnectionState.PROTOCOL_ERROR,
        ShizukuConnectionState.UNTRUSTED_BACKEND -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = container,
        contentColor = foreground,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("shizuku-status"),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = status.state.label(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            status.installedVersionName?.let {
                Text(
                    text = "Shizuku $it",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun IdentityRows(status: ShizukuStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        IdentityRow("后端 UID", status.backendUid?.toString() ?: "未连接")
        IdentityRow("实际 UID", status.observedUid?.toString() ?: "待验证")
        IdentityRow("执行身份", status.identity.label())
        IdentityRow("服务版本", status.serviceVersion?.toString() ?: "未知")
    }
}

@Composable
private fun IdentityRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("shizuku-uid"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ActionRow(
    state: ShizukuUiState,
    onRequestPermission: () -> Unit,
    onVerifyIdentity: () -> Unit,
    onListPackages: () -> Unit,
    onCancel: () -> Unit,
) {
    val busy = state.runningCommand != null
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.status.canRequestPermission) {
            OutlinedButton(
                onClick = onRequestPermission,
                enabled = !state.permissionRequestInFlight,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("shizuku-request-permission"),
            ) {
                Icon(Icons.Outlined.Key, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("请求授权")
            }
        }
        ReadOnlyCommandActions(state, onVerifyIdentity, onListPackages)
        if (busy) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("shizuku-cancel"),
            ) {
                Text("取消当前命令")
            }
        }
    }
}

@Composable
private fun ReadOnlyCommandActions(
    state: ShizukuUiState,
    onVerifyIdentity: () -> Unit,
    onListPackages: () -> Unit,
) {
    val busy = state.runningCommand != null
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onVerifyIdentity,
            enabled = state.status.canExecute && !busy,
            modifier = Modifier
                .weight(1f)
                .testTag("shizuku-verify"),
        ) {
            Icon(Icons.Outlined.Fingerprint, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.runningCommand == ExecutionCommand.Identity) "验证中" else "验证身份",
            )
        }
        OutlinedButton(
            onClick = onListPackages,
            enabled = state.status.canExecute && !busy,
            modifier = Modifier
                .weight(1f)
                .testTag("shizuku-packages"),
        ) {
            Icon(Icons.Outlined.Inventory2, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.runningCommand == ExecutionCommand.PackageList) "读取中" else "列出包",
            )
        }
    }
}

@Composable
private fun ResultPanel(result: ExecutionResult?) {
    if (result == null) {
        Text(
            text = "暂无执行结果",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    when (result) {
        is ExecutionResult.Failure -> FailurePanel(result.failure.code)
        is ExecutionResult.Success -> ReceiptPanel(result.receipt)
    }
}

@Composable
private fun FailurePanel(code: ExecutionFailureCode) {
    Text(
        text = "执行失败：${code.label()}",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.testTag("shizuku-failure"),
    )
}

@Composable
private fun ReceiptPanel(receipt: ExecutionReceipt) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "退出码 ${receipt.exitCode} · ${receipt.durationMillis} ms · " +
                "UID ${receipt.identity.backendUid}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("shizuku-receipt"),
        )
        OutputBlock("stdout", receipt.output.stdout, receipt.output.stdoutTruncated)
        OutputBlock("stderr", receipt.output.stderr, receipt.output.stderrTruncated)
    }
}

@Composable
private fun OutputBlock(label: String, text: String, truncated: Boolean) {
    Column {
        Text(
            text = if (truncated) "$label · 已截断" else label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = text.ifEmpty { "（空）" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(10.dp)
                    .testTag("shizuku-output"),
            )
        }
    }
}

private val connectionLabels = mapOf(
    ShizukuConnectionState.NOT_INSTALLED to "未安装 Shizuku",
    ShizukuConnectionState.NOT_RUNNING to "Shizuku 服务未运行",
    ShizukuConnectionState.PERMISSION_REQUIRED to "等待授权",
    ShizukuConnectionState.READY to "已连接",
    ShizukuConnectionState.REVOKED to "授权已撤销",
    ShizukuConnectionState.BINDER_DEAD to "连接已断开",
    ShizukuConnectionState.PROTOCOL_ERROR to "连接协议失败",
    ShizukuConnectionState.UNTRUSTED_BACKEND to "后端身份不受信任",
    ShizukuConnectionState.CLOSED to "执行服务已关闭",
)

private val identityLabels = mapOf(
    ShizukuIdentity.UNKNOWN to "未知",
    ShizukuIdentity.UNVERIFIED to "待验证",
    ShizukuIdentity.SHELL to "shell",
    ShizukuIdentity.ROOT to "root",
    ShizukuIdentity.MISMATCH to "UID 不一致",
    ShizukuIdentity.UNTRUSTED to "不受信任",
)

private val failureLabels = mapOf(
    ExecutionFailureCode.NOT_INSTALLED to "未安装",
    ExecutionFailureCode.SERVICE_NOT_RUNNING to "服务未运行",
    ExecutionFailureCode.PERMISSION_REQUIRED to "需要授权",
    ExecutionFailureCode.PERMISSION_REVOKED to "授权已撤销",
    ExecutionFailureCode.BINDER_DEAD to "连接已断开",
    ExecutionFailureCode.BINDER_PROTOCOL_FAILED to "连接协议失败",
    ExecutionFailureCode.UNTRUSTED_BACKEND_UID to "后端 UID 不受信任",
    ExecutionFailureCode.UID_MISMATCH to "实际 UID 不一致",
    ExecutionFailureCode.INVALID_REQUEST to "请求无效",
    ExecutionFailureCode.PROCESS_START_FAILED to "进程启动失败",
    ExecutionFailureCode.IO_FAILED to "读写失败",
    ExecutionFailureCode.TIMEOUT to "执行超时",
    ExecutionFailureCode.CANCELLED to "执行已取消",
    ExecutionFailureCode.SYSTEM_CLOSED to "执行服务已关闭",
)

private fun ShizukuConnectionState.label(): String = connectionLabels.getValue(this)

private fun ShizukuIdentity.label(): String = identityLabels.getValue(this)

private fun ExecutionFailureCode.label(): String = failureLabels.getValue(this)
