package com.deepstudent.app.content

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.deepstudent.features.browser.backend.domain.BrowserUrlPolicy
import com.deepstudent.features.browser.data.AndroidBrowserOwner
import com.deepstudent.features.browser.ui.BrowserScreen

@Composable
fun BrowserEntry(onClose: () -> Unit, modifier: Modifier = Modifier, host: BrowserEntryHost = BrowserEntryHost()) {
    val activity = LocalContext.current.activityOrNull()
    if (activity == null) {
        EntryUnavailable("浏览器暂不可用", modifier)
        return
    }
    val result = remember(activity) {
        runCatching { AndroidBrowserOwner(activity, BrowserUrlPolicy(host.allowLoopback)) }
            .onFailure { logOpenFailure("browser", it) }
    }
    val owner = result.getOrNull()
    if (owner == null) {
        EntryUnavailable("浏览器暂不可用", modifier)
        return
    }
    DisposableEffect(owner) { onDispose { owner.close() } }
    val state by owner.state.collectAsState()
    LaunchedEffect(owner) { host.initialUrl?.takeIf { it.isNotEmpty() }?.let(owner::navigate) }
    LaunchedEffect(state.url) { if (state.url.isNotEmpty()) host.onUrlChanged(state.url) }
    BrowserScreen(owner, onClose, modifier.fillMaxSize(), active = host.active) {
        AndroidView(factory = { owner.view }, modifier = Modifier.fillMaxSize())
    }
}

class BrowserEntryHost(
    val initialUrl: String? = null,
    val active: Boolean = true,
    val onUrlChanged: suspend (String) -> Unit = {},
    val allowLoopback: Boolean = false,
)
