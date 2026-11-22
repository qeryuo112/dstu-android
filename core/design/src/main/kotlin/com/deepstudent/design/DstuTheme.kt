package com.deepstudent.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalDstuTokens = staticCompositionLocalOf {
    DstuTokens.resolve(DstuMode.Light)
}

object DstuTheme {
    val tokens: DstuDesignTokens @Composable get() = LocalDstuTokens.current
    val colors: DstuColorScheme @Composable get() = tokens.colors
    val sizes: DstuSizes @Composable get() = tokens.sizes
    val typography: Typography @Composable get() = MaterialTheme.typography
    val shapes: Shapes @Composable get() = MaterialTheme.shapes
}

@Composable
fun DstuTheme(
    mode: DstuMode = if (isSystemInDarkTheme()) DstuMode.Dark else DstuMode.Light,
    palette: DstuPalette = DstuPalette.Default,
    content: @Composable () -> Unit,
) {
    val tokens = remember(mode, palette) { DstuTokens.resolve(mode, palette) }
    val colors = remember(mode, tokens) { materialColors(mode, tokens.colors) }
    val typography = remember(tokens) { materialTypography(tokens.type) }
    val shapes = remember(tokens) { materialShapes(tokens.sizes) }
    CompositionLocalProvider(LocalDstuTokens provides tokens) {
        MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
    }
}
