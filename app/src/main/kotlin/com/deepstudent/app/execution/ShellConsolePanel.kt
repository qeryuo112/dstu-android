package com.deepstudent.app.execution

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.deepstudent.services.execution.api.ShellCommand
import com.deepstudent.services.execution.api.ShellConsole
import com.deepstudent.services.execution.api.ShellResult
import com.deepstudent.services.execution.api.ShellTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
internal fun ShellConsolePanel(console: ShellConsole, openApprovals: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state by console.state.collectAsState()
    var target by remember { mutableStateOf(ShellTarget.APP) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("选择执行身份。Linux 需要已安装的环境；Shizuku 和 Root 需要系统已有授权。")
        TargetPicker(target) { target = it }
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().testTag("shell-command"), label = { Text("命令") }, minLines = 2)
        Button(enabled = input.isNotBlank() && !state.waitingOrRunning, onClick = {
            scope.launch {
                error = try {
                    console.submit(ShellCommand(target, input))
                    input = ""
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    "无法提交命令"
                }
            }
        }, modifier = Modifier.testTag("shell-submit")) { Text("申请执行") }
        state.scope?.let { Text("${it.target.name} · UID ${it.uid} · ${it.directory}") }
        if (state.waitingOrRunning) {
            Text("等待审批或命令结束")
            TextButton(openApprovals, Modifier.testTag("shell-open-approvals")) { Text("查看执行审批") }
            val task = state.taskId
            if (task != null) TextButton({ scope.launch { console.revoke(task) } }, Modifier.testTag("shell-revoke")) { Text("撤权并停止") }
        }
        error?.let { Text(it) }
        state.result?.let { ShellResultText(it) }
    }
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun TargetPicker(target: ShellTarget, onPick: (ShellTarget) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (item in ShellTarget.entries) {
            FilterChip(item == target, { onPick(item) }, { Text(item.name) }, Modifier.testTag("shell-target-${item.name}"))
        }
    }
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun ShellResultText(result: ShellResult) {
    when (result) {
        is ShellResult.Unavailable -> Text("该目标未能执行：${result.code.name}", Modifier.testTag("shell-result"))

        is ShellResult.Blocked -> Text("命令已停止或未获授权：${result.reason}", Modifier.testTag("shell-result"))

        is ShellResult.Exited -> with(result.receipt) {
            Text("已退出：$exitCode · 实测 UID $observedUid · $durationMillis ms", Modifier.testTag("shell-result"))
            Text(output.stdout, fontFamily = FontFamily.Monospace)
            if (output.stderr.isNotEmpty()) Text(output.stderr, fontFamily = FontFamily.Monospace)
            if (output.stdoutTruncated || output.stderrTruncated) Text("输出已按预算截断")
        }
    }
}
