package com.deepstudent.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun DstuTokenBoard(mode: DstuMode, palette: DstuPalette, onAction: () -> Unit = {}) {
    DstuTheme(mode, palette) {
        Surface(Modifier.fillMaxSize().testTag("dstu-token-board")) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DstuBrandMark("Deep Student mark", Modifier.size(40.dp))
                    DstuBrandLogo("Deep Student", Modifier.height(40.dp))
                }
                Text("${mode.id} / ${palette.id}", style = MaterialTheme.typography.titleLarge)
                TokenSwatches()
                Text("Deep Student 设计系统", style = MaterialTheme.typography.headlineMedium)
                Text("笔记 / Notes 123", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAction, modifier = Modifier.testTag("board-primary")) { Text("Save") }
                    TextButton(onClick = onAction) { Text("Cancel") }
                    DstuNavigationIcon(DstuNavigationIcon.Library, "Library", Modifier.size(24.dp))
                    DstuNavigationIcon(DstuNavigationIcon.Settings, "Settings", Modifier.size(24.dp), DstuIconWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TokenSwatches() {
    val colors = DstuTheme.colors
    val entries = listOf(
        "primary" to colors.primary,
        "background" to colors.background,
        "surface" to colors.surface,
        "foreground" to colors.foreground,
        "border" to colors.border,
        "danger" to colors["--danger"],
        "success" to colors["--success"],
        "warning" to colors["--warning"],
    )
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        entries.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { (name, color) -> Swatch(name, color, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Surface(Modifier.fillMaxWidth().height(34.dp).testTag("swatch-$name").background(color), color = color) {}
        Text(name, style = MaterialTheme.typography.labelSmall)
    }
}
