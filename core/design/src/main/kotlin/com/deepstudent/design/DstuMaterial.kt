package com.deepstudent.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal fun materialColors(mode: DstuMode, c: DstuColorScheme): ColorScheme {
    val base = if (mode == DstuMode.Dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c["--primary"], onPrimary = c["--primary-foreground"],
        primaryContainer = c["--accent"], onPrimaryContainer = c["--accent-foreground"],
        inversePrimary = c["--ring"],
        secondary = c["--secondary"], onSecondary = c["--secondary-foreground"],
        secondaryContainer = c["--muted"], onSecondaryContainer = c["--muted-foreground"],
        tertiary = c["--accent-primary"], onTertiary = c["--primary-foreground"],
        tertiaryContainer = c["--accent"], onTertiaryContainer = c["--accent-foreground"],
        background = c["--background"], onBackground = c["--foreground"],
        surface = c["--card"], onSurface = c["--card-foreground"],
        surfaceVariant = c["--muted"], onSurfaceVariant = c["--muted-foreground"],
        surfaceTint = c["--primary"], inverseSurface = c["--foreground"], inverseOnSurface = c["--background"],
        surfaceBright = c["--surface-elevated"], surfaceDim = c["--surface-muted"],
        surfaceContainer = c["--surface-panel"], surfaceContainerLow = c["--surface-root"],
        surfaceContainerLowest = c["--background"], surfaceContainerHigh = c["--surface-panel-strong"],
        surfaceContainerHighest = c["--surface-muted"],
        outline = c["--border"], outlineVariant = c["--border-soft"],
        error = c["--destructive"], onError = c["--destructive-foreground"],
        errorContainer = c["--button-destructive-bg"], onErrorContainer = c["--destructive-foreground"],
        scrim = c["--overlay"],
        primaryFixed = c["--primary"], primaryFixedDim = c["--ring"],
        onPrimaryFixed = c["--primary-foreground"], onPrimaryFixedVariant = c["--primary-foreground"],
        secondaryFixed = c["--secondary"], secondaryFixedDim = c["--muted"],
        onSecondaryFixed = c["--secondary-foreground"], onSecondaryFixedVariant = c["--muted-foreground"],
        tertiaryFixed = c["--accent-primary"], tertiaryFixedDim = c["--ring"],
        onTertiaryFixed = c["--primary-foreground"], onTertiaryFixedVariant = c["--primary-foreground"],
    )
}

private fun textStyle(tokens: DstuTypeTokens, size: String, weight: String, lineHeight: String): TextStyle {
    val fontSize = tokens.size(size)
    return TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = fontSize,
        fontWeight = FontWeight(tokens.weight(weight)),
        lineHeight = (fontSize.value * tokens.number(lineHeight)).sp,
        letterSpacing = 0.sp,
    )
}

internal fun materialTypography(t: DstuTypeTokens): Typography {
    val display = textStyle(t, "--font-size-3xl", "--font-weight-bold", "--line-height-tight")
    val headline = textStyle(t, "--font-size-2xl", "--font-weight-semibold", "--line-height-snug")
    val title = textStyle(t, "--font-size-lg", "--font-weight-semibold", "--line-height-snug")
    val body = textStyle(t, "--font-size-base", "--font-weight-normal", "--line-height-normal")
    val small = textStyle(t, "--font-size-sm", "--font-weight-normal", "--line-height-normal")
    val label = textStyle(t, "--font-size-ui", "--font-weight-medium", "--line-height-normal")
    return Typography(
        displayLarge = display, displayMedium = display, displaySmall = display,
        headlineLarge = headline, headlineMedium = headline, headlineSmall = headline,
        titleLarge = title, titleMedium = title, titleSmall = label,
        bodyLarge = body, bodyMedium = body, bodySmall = small,
        labelLarge = label, labelMedium = label, labelSmall = small,
    )
}

internal fun materialShapes(sizes: DstuSizes): Shapes = Shapes(
    extraSmall = RoundedCornerShape(sizes["--button-radius"]),
    small = RoundedCornerShape(sizes["--radius"]),
    medium = RoundedCornerShape(sizes["--radius-card"]),
    large = RoundedCornerShape(sizes["--radius-shell-panel"]),
    extraLarge = RoundedCornerShape(sizes["--radius-shell-dialog"]),
)
