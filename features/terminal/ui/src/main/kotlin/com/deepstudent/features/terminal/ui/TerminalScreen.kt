package com.deepstudent.features.terminal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.terminal.api.TerminalFailure
import com.deepstudent.features.terminal.api.TerminalPhase
import com.deepstudent.features.terminal.api.TerminalSessionId
import com.deepstudent.features.terminal.api.TerminalSessionSnapshot

@Composable
fun TerminalScreen(
    state: TerminalUiState,
    actions: TerminalActions,
    onClosePage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag("terminal-screen"),
        color = DstuTheme.colors.background,
        contentColor = DstuTheme.colors.foreground,
    ) {
        Column(Modifier.fillMaxSize()) {
            TerminalHeader(state.busy, actions, onClosePage)
            TerminalTabs(state, actions)
            state.failure?.let { Text(failureLabel(it)) }
            val selected = state.snapshot
            val selectedId = state.selectedId
            if (selected == null || selectedId == null) {
                Text("暂无会话")
            } else {
                key(selectedId) {
                    TerminalSessionContent(selectedId, selected, actions)
                }
            }
        }
    }
}

@Composable
private fun TerminalHeader(
    busy: Boolean,
    actions: TerminalActions,
    onClosePage: () -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        Text("终端", Modifier.weight(1f))
        TerminalIconButton("新建会话", onClick = actions.create, enabled = !busy) {
            Icon(Icons.Default.Add, contentDescription = null)
        }
        TerminalIconButton("关闭终端", onClick = onClosePage) {
            Icon(Icons.Default.Close, contentDescription = null)
        }
    }
}

@Composable
private fun TerminalTabs(state: TerminalUiState, actions: TerminalActions) {
    if (state.sessions.isEmpty()) return
    val selectedIndex = state.sessions.indexOfFirst { it.id == state.selectedId }.coerceAtLeast(0)
    PrimaryScrollableTabRow(selectedTabIndex = selectedIndex) {
        state.sessions.forEach { summary ->
            Tab(
                selected = summary.id == state.selectedId,
                onClick = { actions.select(summary.id) },
                text = { Text("会话 ${summary.ordinal}") },
            )
        }
    }
}

@Composable
private fun ColumnScope.TerminalSessionContent(
    sessionId: TerminalSessionId,
    snapshot: TerminalSessionSnapshot,
    actions: TerminalActions,
) {
    val running = snapshot.phase == TerminalPhase.RUNNING
    TerminalViewport(snapshot.screen, Modifier.weight(1f)) { size ->
        if (running && size != snapshot.screen.size) actions.resize(size)
    }
    Row(Modifier.fillMaxWidth()) {
        Text(statusText(snapshot.phase, snapshot.exitCode), Modifier.weight(1f))
        TerminalIconButton("停止会话", onClick = actions.terminate, enabled = running) {
            Icon(Icons.Default.Stop, contentDescription = null)
        }
        TerminalIconButton("关闭会话", onClick = { actions.close(sessionId) }) {
            Icon(Icons.Default.Close, contentDescription = null)
        }
    }
    snapshot.failure?.let { Text(failureLabel(it.toUi())) }
    TerminalInput(
        enabled = running,
        onInput = actions.write,
        onInterrupt = actions.interrupt,
    )
}

private fun statusText(phase: TerminalPhase, exitCode: Int?): String = when (phase) {
    TerminalPhase.STARTING -> "正在启动"
    TerminalPhase.RUNNING -> "运行中"
    TerminalPhase.STOPPING -> "正在停止"
    TerminalPhase.EXITED -> "已退出，退出码：${exitCode ?: "未知"}"
    TerminalPhase.FAILED -> "运行失败"
}

private fun failureLabel(failure: TerminalUiFailure): String = when (failure) {
    TerminalUiFailure.START_FAILED -> "终端启动失败"
    TerminalUiFailure.IO_FAILED -> "终端输入输出失败"
    TerminalUiFailure.RESIZE_FAILED -> "终端尺寸调整失败"
    TerminalUiFailure.STOP_FAILED -> "终端停止失败"
    TerminalUiFailure.SESSION_CLOSED -> "终端会话已关闭"
    TerminalUiFailure.UNKNOWN -> "终端操作失败"
}

private fun TerminalFailure.toUi(): TerminalUiFailure = when (this) {
    TerminalFailure.START_FAILED -> TerminalUiFailure.START_FAILED
    TerminalFailure.IO_FAILED -> TerminalUiFailure.IO_FAILED
    TerminalFailure.RESIZE_FAILED -> TerminalUiFailure.RESIZE_FAILED
    TerminalFailure.STOP_FAILED -> TerminalUiFailure.STOP_FAILED
    TerminalFailure.SESSION_CLOSED -> TerminalUiFailure.SESSION_CLOSED
    TerminalFailure.EXIT_STATUS_UNAVAILABLE -> TerminalUiFailure.UNKNOWN
}
