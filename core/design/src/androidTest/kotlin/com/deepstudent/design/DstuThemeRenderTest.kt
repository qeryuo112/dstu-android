package com.deepstudent.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.sp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File

class DstuThemeRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = DstuTestHost()

    @Before fun startHost() = host.start()

    @After fun closeHost() = host.close()

    @Test(timeout = 120_000)
    fun boardRendersAllPalettesAndPrimaryPixelsMatchTokens() {
        var mode by mutableStateOf(DstuMode.Light)
        var palette by mutableStateOf(DstuPalette.Default)
        var clicks = 0
        host.setContent { DstuTokenBoard(mode, palette) { clicks++ } }
        for (nextMode in DstuMode.entries) for (nextPalette in DstuPalette.entries) {
            compose.runOnIdle { mode = nextMode; palette = nextPalette }
            compose.onNodeWithTag("dstu-token-board").assertIsDisplayed()
            compose.onNodeWithContentDescription("Deep Student mark").assertIsDisplayed()
            compose.onNodeWithContentDescription("Library").assertIsDisplayed()
            val image = compose.onNodeWithTag("swatch-primary").captureToImage().toPixelMap()
            val pixel = image[image.width / 2, image.height / 2].toArgb()
            assertEquals("${nextMode.id}/${nextPalette.id} rendered primary", DstuTokens.resolve(nextMode, nextPalette).colors.primary.toArgb(), pixel)
            if (nextPalette == DstuPalette.Default) captureBoard(nextMode)
        }
        compose.onNodeWithTag("board-primary").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    private fun captureBoard(mode: DstuMode) {
        val image = compose.onNodeWithTag("dstu-token-board").captureToImage().asAndroidBitmap()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "s1-board-${mode.id}.png")
        file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test
    fun materialRolesAndTypographyUseFrozenTokenValues() {
        for (mode in DstuMode.entries) for (palette in DstuPalette.entries) {
            val tokens = DstuTokens.resolve(mode, palette)
            val scheme = materialColors(mode, tokens.colors)
            val material = listOf(
                scheme.primary, scheme.onPrimary, scheme.primaryContainer, scheme.onPrimaryContainer, scheme.inversePrimary,
                scheme.secondary, scheme.onSecondary, scheme.secondaryContainer, scheme.onSecondaryContainer,
                scheme.tertiary, scheme.onTertiary, scheme.tertiaryContainer, scheme.onTertiaryContainer,
                scheme.background, scheme.onBackground, scheme.surface, scheme.onSurface, scheme.surfaceVariant,
                scheme.onSurfaceVariant, scheme.surfaceTint, scheme.inverseSurface, scheme.inverseOnSurface,
                scheme.error, scheme.onError, scheme.errorContainer, scheme.onErrorContainer, scheme.outline,
                scheme.outlineVariant, scheme.scrim, scheme.surfaceBright, scheme.surfaceDim, scheme.surfaceContainer,
                scheme.surfaceContainerHigh, scheme.surfaceContainerHighest, scheme.surfaceContainerLow,
                scheme.surfaceContainerLowest, scheme.primaryFixed, scheme.primaryFixedDim, scheme.onPrimaryFixed,
                scheme.onPrimaryFixedVariant, scheme.secondaryFixed, scheme.secondaryFixedDim, scheme.onSecondaryFixed,
                scheme.onSecondaryFixedVariant, scheme.tertiaryFixed, scheme.tertiaryFixedDim, scheme.onTertiaryFixed,
                scheme.onTertiaryFixedVariant,
            )
            assertEquals(48, material.size)
            assertTrue("${mode.id}/${palette.id} has a non-token Material color", material.all { it in tokens.colors.values.values })
            assertEquals(tokens.colors.primary, scheme.primary)
            assertEquals(tokens.colors["--surface-panel"], scheme.surfaceContainer)
            assertTypography(materialTypography(tokens.type))
        }
    }

    private fun assertTypography(typography: androidx.compose.material3.Typography) {
        val styles = listOf(
            typography.displayLarge, typography.displayMedium, typography.displaySmall,
            typography.headlineLarge, typography.headlineMedium, typography.headlineSmall,
            typography.titleLarge, typography.titleMedium, typography.titleSmall,
            typography.bodyLarge, typography.bodyMedium, typography.bodySmall,
            typography.labelLarge, typography.labelMedium, typography.labelSmall,
        )
        assertTrue(styles.all { it.letterSpacing == 0.sp && it.color == Color.Unspecified })
    }

    @Test(timeout = 60_000)
    fun compositionProvidesSelectedColorsAndSupportsModeChanges() {
        var mode by mutableStateOf(DstuMode.Light)
        var observed = Color.Unspecified
        host.setContent { DstuTheme(mode, DstuPalette.Teal) { observed = MaterialTheme.colorScheme.primary } }
        compose.runOnIdle { assertEquals(DstuTokens.resolve(mode, DstuPalette.Teal).colors.primary, observed) }
        compose.runOnIdle { mode = DstuMode.Dark }
        compose.runOnIdle { assertEquals(DstuTokens.resolve(mode, DstuPalette.Teal).colors.primary, observed) }
    }
}
