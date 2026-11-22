package com.deepstudent.features.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.browser.api.BrowserSession

@Composable
fun BrowserScreen(session: BrowserSession, onClose: () -> Unit, modifier: Modifier = Modifier,
    active: Boolean = true, content: @Composable () -> Unit) {
    val state by session.state.collectAsState()
    var address by remember(state.url) { mutableStateOf(state.url) }
    val close = { session.close(); onClose() }
    BackHandler(enabled = active && !state.closed) { if (!session.goBack()) close() }
    DisposableEffect(session) { onDispose { session.close() } }
    Surface(modifier = modifier, color = DstuTheme.colors.background, contentColor = DstuTheme.colors.foreground) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = { session.goBack() }, enabled = state.canGoBack && !state.closed) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = LocalContentColor.current)
                }
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, modifier = Modifier.weight(1f),
                    singleLine = true, label = { Text("Address") }, enabled = !state.closed,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { session.navigate(address) }),
                )
                IconButton(onClick = session::reload, enabled = !state.closed) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = LocalContentColor.current)
                }
                IconButton(onClick = close, enabled = !state.closed) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = LocalContentColor.current)
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it) }
            if (!state.closed) androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { content() }
            else Text("Browser closed")
        }
    }
}
