package com.deepstudent.features.chat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.features.chat.api.ChatStreamPhase
import com.deepstudent.features.chat.api.ChatStreamSnapshot
import com.deepstudent.features.chat.api.ChatStreams
import kotlinx.coroutines.launch

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
fun ChatStreamPanel(streams: ChatStreams, taskId: TaskId?, onTask: (TaskId) -> Unit, modifier: Modifier = Modifier) {
    var endpoint by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var snapshot by remember(taskId) { mutableStateOf<ChatStreamSnapshot?>(null) }
    val scope = rememberCoroutineScope()
    val onTaskState = rememberUpdatedState(onTask)
    val actions = remember(streams) { ChatPanelActions(streams) { onTaskState.value(it) } }
    LaunchedEffect(streams, taskId) {
        taskId?.let { id -> streams.observe(id).collect { snapshot = it } }
    }
    val running = actions.submitting || isRunning(taskId, snapshot)
    Column(modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            endpoint,
            { endpoint = it },
            Modifier.fillMaxWidth().testTag("chat-endpoint"),
            label = { Text("SSE 地址") },
            singleLine = true,
            enabled = !running,
        )
        OutlinedTextField(
            model,
            { model = it },
            Modifier.fillMaxWidth().testTag("chat-model"),
            label = { Text("模型（可选）") },
            singleLine = true,
            enabled = !running,
        )
        Text(
            snapshot?.text.orEmpty(),
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState()).testTag("chat-output"),
        )
        snapshot?.phase?.takeIf { it != ChatStreamPhase.Running }?.let { Text(phaseLabel(it)) }
        if (actions.failed) Text("无法启动请求，请检查地址")
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                prompt,
                { prompt = it },
                Modifier.weight(1f).testTag("chat-prompt"),
                label = { Text("消息") },
                enabled = !running,
                maxLines = 4,
            )
            IconButton(onClick = {
                scope.launch { actions.submit(running, taskId, endpoint, prompt, model) }
            }, enabled = !actions.submitting, modifier = Modifier.testTag("chat-submit")) {
                Icon(
                    if (running) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    if (running) "停止" else "发送",
                )
            }
        }
    }
}

private fun isRunning(taskId: TaskId?, snapshot: ChatStreamSnapshot?): Boolean =
    taskId != null && (snapshot == null || snapshot.phase == ChatStreamPhase.Running)

private fun phaseLabel(phase: ChatStreamPhase): String = when (phase) {
    ChatStreamPhase.Completed -> "已完成"
    ChatStreamPhase.Cancelled -> "已停止"
    ChatStreamPhase.Failed -> "请求失败"
    ChatStreamPhase.Interrupted -> "请求已中断"
    ChatStreamPhase.Running -> "生成中"
}
