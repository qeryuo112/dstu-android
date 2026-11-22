package com.deepstudent.features.content.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.content.api.ContentSession
import com.deepstudent.features.content.api.ContentStatus
import com.deepstudent.features.content.api.ContentTheme

@Composable
fun ContentScreen(session: ContentSession, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val status by session.status.collectAsState()
    val colors = DstuTheme.colors
    val theme = ContentTheme(colors.background.luminance() < 0.5f, css(colors.background), css(colors.foreground), css(colors.primary))
    SideEffect { session.setTheme(theme) }
    DisposableEffect(session) { onDispose { session.close() } }
    Surface(modifier = modifier, color = colors.background, contentColor = colors.foreground) {
        Box(Modifier.fillMaxSize()) {
            if (status != ContentStatus.Closed) content()
            when (status) {
                ContentStatus.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ContentStatus.Failed -> Text("Content unavailable", Modifier.align(Alignment.Center))
                ContentStatus.Closed -> Text("Content closed", Modifier.align(Alignment.Center))
                ContentStatus.Ready -> Unit
            }
        }
    }
}

private fun css(color: Color): String = "#%06x".format(color.toArgb() and 0xffffff)
