package com.deepstudent.features.browser.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.deepstudent.design.DstuMode
import com.deepstudent.design.DstuTheme
import com.deepstudent.design.DstuTokens
import com.deepstudent.features.browser.api.BrowserSession
import com.deepstudent.features.browser.api.BrowserState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BrowserFakeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = BrowserTestHost()
    @Before fun start() = host.start()
    @After fun close() = host.close()

    @Test fun darkSurfaceActionsAndCloseUseFacade() {
        val session = FakeBrowser()
        var closed = false
        var observed by mutableStateOf(Color.Unspecified)
        host.setContent {
            DstuTheme(DstuMode.Dark) {
                BrowserScreen(session, { closed = true }) { observed = LocalContentColor.current }
            }
        }
        compose.onNodeWithContentDescription("Reload").assertIsDisplayed().performClick()
        val pixels = compose.onNodeWithContentDescription("Reload").captureToImage().toPixelMap()
        val foreground = DstuTokens.resolve(DstuMode.Dark).colors.foreground.toArgb()
        assertTrue((0 until pixels.width).any { x -> (0 until pixels.height).any { y -> pixels[x, y].toArgb() == foreground } })
        compose.runOnIdle {
            assertEquals(1, session.reloads)
            assertEquals(DstuTokens.resolve(DstuMode.Dark).colors.foreground, observed)
        }
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle { assertTrue(closed); assertTrue(session.state.value.closed) }
    }

    private class FakeBrowser : BrowserSession {
        override val state = MutableStateFlow(BrowserState(url = "https://example.org", canGoBack = true))
        var reloads = 0
        override fun navigate(url: String) { state.value = state.value.copy(url = url) }
        override fun goBack() = true
        override fun reload() { reloads++ }
        override fun close() { state.value = state.value.copy(closed = true) }
    }
}
