package com.deepstudent.features.content.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.deepstudent.design.DstuMode
import com.deepstudent.design.DstuTheme
import com.deepstudent.design.DstuTokens
import com.deepstudent.features.content.api.ContentSession
import com.deepstudent.features.content.api.ContentStatus
import com.deepstudent.features.content.api.ContentTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ContentFakeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = ContentTestHost()
    @Before fun start() = host.start()
    @After fun close() = host.close()

    @Test fun themeChangesAndDisposeReachOnlySession() {
        val session = FakeContent()
        var mode by mutableStateOf(DstuMode.Light)
        var visible by mutableStateOf(true)
        var observed = Color.Unspecified
        host.setContent {
            DstuTheme(mode) { if (visible) ContentScreen(session) { observed = LocalContentColor.current } }
        }
        compose.runOnIdle { assertFalse(session.currentTheme!!.dark); mode = DstuMode.Dark }
        compose.runOnIdle {
            assertTrue(session.currentTheme!!.dark)
            assertEquals(DstuTokens.resolve(DstuMode.Dark).colors.foreground, observed)
            visible = false
        }
        compose.runOnIdle { assertEquals(ContentStatus.Closed, session.status.value) }
    }

    private class FakeContent : ContentSession {
        override val status = MutableStateFlow(ContentStatus.Ready)
        var currentTheme: ContentTheme? = null
        override fun setTheme(theme: ContentTheme) { this.currentTheme = theme }
        override fun close() { status.value = ContentStatus.Closed }
    }
}
