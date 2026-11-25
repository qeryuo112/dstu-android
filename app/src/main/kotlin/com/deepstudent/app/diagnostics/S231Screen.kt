package com.deepstudent.app.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.deepstudent.design.DstuTheme

/** Normal and isolated apps use the same user-initiated, sanitized diagnostics entry. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun S231Host(content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    DstuTheme {
        Scaffold(bottomBar = {
            TextButton(onClick = { open = !open }) { Text(if (open) "返回应用" else "诊断与错误") }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // Keep the workspace alive while diagnostics covers it, but hide it from accessibility focus.
                val contentModifier = if (open) Modifier.clearAndSetSemantics { } else Modifier
                Box(Modifier.fillMaxSize().then(contentModifier)) { content() }
                if (open) Surface(Modifier.fillMaxSize()) { S231Screen(S231Diagnostics.buffer::snapshot) }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun S231Screen(snapshot: () -> S231Snapshot) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var current by remember { mutableStateOf(snapshot()) }
    var shareError by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("诊断与错误")
        Text("仅保存最近 128 条脱敏事件，不读取系统日志；突然关闭时最新记录可能尚未写入。")
        Text("记录 ${current.entries.size} 条")
        S231Controls(
            refresh = { current = snapshot() },
            share = {
                shareError = runCatching {
                    val intent = S231Share.intent(context, current)
                    context.startActivity(Intent.createChooser(intent, "分享脱敏诊断"))
                }.isFailure
            },
        )
        if (shareError) Text("无法创建分享，请重试。")
        current.entries.forEachIndexed { index, entry ->
            S231Record(index, entry) { clipboard.setText(AnnotatedString(it)) }
        }
        S231Catalogue { clipboard.setText(AnnotatedString(it)) }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun S231Controls(refresh: () -> Unit, share: () -> Unit) {
    Row {
        TextButton(onClick = refresh) { Text("刷新") }
        Button(onClick = share) { Text("分享诊断文件") }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun S231Record(index: Int, entry: S231Entry, copy: (String) -> Unit) {
    Text("${index + 1}. ${entry.event?.name ?: "其他事件"} ${entry.level.name}")
    Text(entry.identities())
    Text(entry.progressLabel())
    TextButton(onClick = { copy(S231TextBundle.create(S231Snapshot(listOf(entry))).toString(Charsets.UTF_8)) }) {
        Text("复制此条诊断（脱敏）")
    }
    entry.error?.let { error ->
        TextButton(onClick = { copy(error.name) }) { Text("复制错误码 ${error.name}") }
    }
}

private fun S231Entry.identities(): String = "库 ${library?.let { "L$it" } ?: "-"} · 数据库 ${location.database ?: "-"} · " +
    "任务 ${task?.let { "T$it" } ?: "-"} · 关联 ${correlation?.let { "C$it" } ?: "-"}"

private fun S231Entry.progressLabel(): String =
    "阶段 ${location.stage ?: location.phase ?: "-"} · 状态 ${progress.status ?: progress.state ?: progress.outcome ?: "-"}"

@Suppress("ktlint:standard:function-naming")
@Composable
private fun S231Catalogue(copy: (String) -> Unit) {
    Text("错误目录")
    S231ErrorCode.entries.forEach { error ->
        TextButton(onClick = { copy(error.name) }) { Text("复制 ${error.name}：${error.asDstuError().message}") }
    }
}
