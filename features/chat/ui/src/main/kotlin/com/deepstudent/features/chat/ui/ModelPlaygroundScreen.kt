package com.deepstudent.features.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepstudent.features.chat.api.ModelPlaygroundFailure
import com.deepstudent.features.chat.api.ModelPlaygroundPorts
import com.deepstudent.features.chat.api.ModelPlaygroundTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Developer playground for one model connection. Values that cannot embed secrets (provider, model,
 * protocol, auth mode and credential reference) survive configuration changes. The endpoint, prompt
 * and header text stay in memory only: an endpoint may carry userinfo or query credentials, headers
 * may carry API keys, and the prompt may contain user content. Streamed output is transient.
 */
@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
fun ModelPlaygroundScreen(ports: ModelPlaygroundPorts, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state = rememberSaveable(saver = PlaygroundState.StateSaver) { PlaygroundState() }
    val sessionId = rememberSaveable { UUID.randomUUID().toString() }
    val scope = rememberCoroutineScope()
    val actions = remember(ports, state, sessionId, scope) { PlaygroundActions(ports, state, sessionId, scope) }
    Surface(
        modifier = modifier.fillMaxSize().testTag("model-playground-screen"),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(Modifier.fillMaxSize()) {
            PlaygroundHeader(streaming = state.streaming, cancelled = state.cancelled, onBack = onBack)
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaygroundEndpointFields(state, enabled = !state.streaming && !state.fetching)
                PlaygroundAuthFields(state, enabled = !state.streaming && !state.fetching)
                PlaygroundModelSection(state, actions)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                PlaygroundOutput(state, sessionId)
            }
            PlaygroundComposer(state, actions)
        }
    }
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun PlaygroundHeader(streaming: Boolean, cancelled: Boolean, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("model-playground-back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
        }
        Text("LLM Playground", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(
            text = statusLabel(streaming, cancelled),
            modifier = Modifier.testTag("model-playground-status"),
            style = MaterialTheme.typography.labelLarge,
            color = if (streaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun statusLabel(streaming: Boolean, cancelled: Boolean): String = when {
    streaming -> "流式中"
    cancelled -> "已取消"
    else -> "空闲"
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun PlaygroundEndpointFields(state: PlaygroundState, enabled: Boolean) {
    OutlinedTextField(
        value = state.provider,
        onValueChange = { state.provider = it },
        modifier = Modifier.fillMaxWidth().testTag("model-playground-provider"),
        label = { Text("供应方（可选）") },
        singleLine = true,
        enabled = enabled,
    )
    OutlinedTextField(
        value = state.endpoint,
        onValueChange = { state.endpoint = it },
        modifier = Modifier.fillMaxWidth().testTag("model-playground-endpoint"),
        label = { Text("接口地址") },
        singleLine = true,
        enabled = enabled,
    )
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun PlaygroundAuthFields(state: PlaygroundState, enabled: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.protocol,
            onValueChange = { state.protocol = it },
            modifier = Modifier.weight(1f).testTag("model-playground-protocol"),
            label = { Text("协议（可选）") },
            singleLine = true,
            enabled = enabled,
        )
        OutlinedTextField(
            value = state.authMode,
            onValueChange = { state.authMode = it },
            modifier = Modifier.weight(1f).testTag("model-playground-auth-mode"),
            label = { Text("鉴权方式（可选）") },
            singleLine = true,
            enabled = enabled,
        )
    }
    OutlinedTextField(
        value = state.credentialReference,
        onValueChange = { state.credentialReference = it },
        modifier = Modifier.fillMaxWidth().testTag("model-playground-credential"),
        label = { Text("凭据引用（可选）") },
        singleLine = true,
        enabled = enabled,
    )
    OutlinedTextField(
        value = state.headers,
        onValueChange = { state.headers = it },
        modifier = Modifier.fillMaxWidth().testTag("model-playground-headers"),
        label = { Text("请求头（每行 Key: value，可选）") },
        minLines = 2,
        maxLines = 4,
        enabled = enabled,
    )
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun PlaygroundModelSection(state: PlaygroundState, actions: PlaygroundActions) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("模型", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = actions::fetch,
            enabled = !state.fetching && !state.streaming,
            modifier = Modifier.testTag("model-playground-fetch"),
        ) {
            Text(if (state.fetching) "拉取中" else "拉取列表")
        }
    }
    if (state.models.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.models.forEachIndexed { index, name ->
                FilterChip(
                    selected = state.model == name,
                    onClick = { state.model = name },
                    label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("model-playground-option-$index"),
                )
            }
        }
    }
    OutlinedTextField(
        value = state.model,
        onValueChange = { state.model = it },
        modifier = Modifier.fillMaxWidth().testTag("model-playground-model"),
        label = { Text("模型") },
        singleLine = true,
        enabled = !state.streaming && !state.fetching,
    )
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun PlaygroundOutput(state: PlaygroundState, sessionId: String) {
    val scroll = rememberScrollState()
    LaunchedEffect(state.output, state.streaming) {
        if (state.streaming) scroll.scrollTo(Int.MAX_VALUE)
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("输出", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(
            text = "会话 ${sessionId.take(8)}",
            modifier = Modifier.testTag("model-playground-session"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth().height(180.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = state.output,
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(8.dp)
                .testTag("model-playground-output"),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
    state.failure?.let { code ->
        Text(
            text = "请求失败：$code",
            modifier = Modifier.testTag("model-playground-failure"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun PlaygroundComposer(state: PlaygroundState, actions: PlaygroundActions) {
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = state.prompt,
            onValueChange = { state.prompt = it },
            modifier = Modifier.weight(1f).testTag("model-playground-prompt"),
            label = { Text("提示词") },
            maxLines = 4,
            enabled = !state.streaming,
        )
        if (state.streaming) {
            IconButton(onClick = actions::cancel, modifier = Modifier.testTag("model-playground-cancel")) {
                Icon(Icons.Default.Stop, "取消")
            }
        } else {
            IconButton(
                onClick = actions::send,
                enabled = !state.fetching && state.prompt.isNotBlank() && state.model.isNotBlank(),
                modifier = Modifier.testTag("model-playground-send"),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "发送")
            }
        }
    }
}

@Stable
private class PlaygroundState {
    var provider by mutableStateOf("")

    /** Endpoint may carry userinfo or query credentials and is deliberately not persisted. */
    var endpoint by mutableStateOf("")
    var model by mutableStateOf("")
    var protocol by mutableStateOf("")
    var authMode by mutableStateOf("")
    var credentialReference by mutableStateOf("")

    /** Prompt may contain user content and is deliberately not persisted. */
    var prompt by mutableStateOf("")

    /** Header values may be secrets, so this text is deliberately excluded from saved state. */
    var headers by mutableStateOf("")
    var models by mutableStateOf<List<String>>(emptyList())
    var output by mutableStateOf("")
    var failure by mutableStateOf<String?>(null)
    var cancelled by mutableStateOf(false)
    var fetching by mutableStateOf(false)
    var streaming by mutableStateOf(false)
    var streamJob: Job? = null

    companion object {
        val StateSaver: Saver<PlaygroundState, Any> = listSaver<PlaygroundState, String>(
            save = {
                listOf(
                    it.provider,
                    it.model,
                    it.protocol,
                    it.authMode,
                    it.credentialReference,
                )
            },
            restore = { values ->
                PlaygroundState().apply {
                    provider = values[0]
                    model = values[1]
                    protocol = values[2]
                    authMode = values[3]
                    credentialReference = values[4]
                }
            },
        )
    }
}

private class PlaygroundActions(
    private val ports: ModelPlaygroundPorts,
    private val state: PlaygroundState,
    private val sessionId: String,
    private val scope: CoroutineScope,
) {
    fun fetch() {
        if (state.fetching || state.streaming) return
        state.cancelled = false
        scope.launch { loadModels() }
    }

    fun send() {
        if (state.streaming || state.fetching) return
        if (state.prompt.isBlank() || state.model.isBlank()) {
            state.failure = "invalid-prompt"
            return
        }
        state.cancelled = false
        state.streamJob = scope.launch { stream() }
    }

    fun cancel() {
        if (!state.streaming) return
        state.cancelled = true
        state.streamJob?.cancel()
    }

    private suspend fun loadModels() {
        state.fetching = true
        state.failure = null
        try {
            val target = target() ?: return
            val found = ports.models(target).distinct()
            state.models = found
            if (state.model.isBlank() && found.isNotEmpty()) state.model = found.first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModelPlaygroundFailure) {
            state.failure = failure.code
        } catch (_: Exception) {
            state.failure = "request-failed"
        } finally {
            state.fetching = false
        }
    }

    private suspend fun stream() {
        state.streaming = true
        state.output = ""
        state.failure = null
        try {
            val target = target() ?: return
            ports.stream(target, state.prompt, sessionId) { chunk -> state.output += chunk }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModelPlaygroundFailure) {
            state.failure = failure.code
        } catch (_: Exception) {
            state.failure = "request-failed"
        } finally {
            state.streaming = false
            state.streamJob = null
        }
    }

    private fun target(): ModelPlaygroundTarget? = buildTarget(state).onFailure { state.failure = "invalid-config" }.getOrNull()
}

private fun buildTarget(state: PlaygroundState): Result<ModelPlaygroundTarget> = runCatching {
    ModelPlaygroundTarget(
        provider = state.provider.trim(),
        endpoint = state.endpoint.trim(),
        model = state.model.trim(),
        protocol = state.protocol.trim().takeIf { it.isNotEmpty() },
        authMode = state.authMode.trim().takeIf { it.isNotEmpty() },
        credentialReference = state.credentialReference.trim().takeIf { it.isNotEmpty() },
        headers = parseHeaders(state.headers),
    )
}

private fun parseHeaders(source: String): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    for (line in source.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val separator = trimmed.indexOf(':')
        require(separator > 0) { "invalid playground header line" }
        headers[trimmed.substring(0, separator).trim()] = trimmed.substring(separator + 1).trim()
    }
    return headers
}
