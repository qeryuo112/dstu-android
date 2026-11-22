package com.deepstudent.app.content

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.content.api.ContentEvent
import com.deepstudent.features.content.api.ContentMessageValidatorFactory
import com.deepstudent.features.content.api.ContentTheme
import com.deepstudent.features.content.backend.domain.ContentMessagePolicy
import com.deepstudent.features.content.backend.domain.ContentResourcePolicy
import com.deepstudent.features.content.data.AndroidContentOwner
import com.deepstudent.features.content.ui.ContentScreen

@Composable
fun ContentEntry(modifier: Modifier = Modifier) {
    var mode by remember { mutableStateOf(ContentMode.Formula) }
    Column(modifier.fillMaxSize()) {
        ContentModeTabs(mode) { mode = it }
        ContentDocumentEntry(mode, Modifier.weight(1f))
    }
}

@Composable
private fun ContentModeTabs(selected: ContentMode, onSelect: (ContentMode) -> Unit) {
    val colors = DstuTheme.colors
    TabRow(selected.ordinal, containerColor = colors.surface, contentColor = colors.primary) {
        ContentMode.entries.forEach { mode ->
            Tab(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                modifier = Modifier.testTag("content-mode-${mode.name.lowercase()}"),
                text = { Text(mode.label, maxLines = 1, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

@Composable
private fun ContentDocumentEntry(mode: ContentMode, modifier: Modifier = Modifier) {
    val activity = LocalContext.current.activityOrNull()
    if (activity == null) {
        EntryUnavailable("内容暂不可用", modifier)
        return
    }
    val colors = DstuTheme.colors
    val theme = ContentTheme(
        colors.background.luminance() < 0.5f,
        cssColor(colors.background), cssColor(colors.foreground), cssColor(colors.primary),
    )
    var event by remember(mode) { mutableStateOf<ContentEvent?>(null) }
    val eventReceiver by rememberUpdatedState<(ContentEvent) -> Unit> { event = it }
    val result = remember(activity, mode) {
        runCatching {
            AndroidContentOwner(
                activity, mode.document, ContentResourcePolicy(emptyList()),
                ContentMessageValidatorFactory { origin, nonce -> ContentMessagePolicy(origin, nonce) },
                theme, { eventReceiver(it) },
            )
        }.onFailure { logOpenFailure("content", it) }
    }
    val owner = result.getOrNull()
    if (owner == null) {
        EntryUnavailable("内容暂不可用", modifier)
        return
    }
    DisposableEffect(owner) { onDispose { owner.close() } }
    Column(modifier.fillMaxSize()) {
        event?.let { ContentEventLabel(it) }
        key(owner) {
            ContentScreen(owner, Modifier.weight(1f)) {
                AndroidView(factory = { owner.view }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ContentEventLabel(event: ContentEvent) {
    val message = when (event) {
        is ContentEvent.Answer -> "回答：${event.value}"
        is ContentEvent.OpenLink -> "链接：${event.url}"
        ContentEvent.Replay -> "已重新开始"
    }
    Text(
        text = message,
        modifier = Modifier.fillMaxWidth().testTag("content-event").padding(horizontal = 16.dp, vertical = 8.dp),
        color = DstuTheme.colors.foreground,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun cssColor(color: Color): String = "#%06x".format(color.toArgb() and 0xffffff)
