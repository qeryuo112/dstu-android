package com.deepstudent.features.terminal.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.terminal.api.TerminalCell
import com.deepstudent.features.terminal.api.TerminalCommands
import com.deepstudent.features.terminal.api.TerminalPhase
import com.deepstudent.features.terminal.api.TerminalQuery
import com.deepstudent.features.terminal.api.TerminalScreen
import com.deepstudent.features.terminal.api.TerminalSessionId
import com.deepstudent.features.terminal.api.TerminalSessionSnapshot
import com.deepstudent.features.terminal.api.TerminalSessionSummary
import com.deepstudent.features.terminal.api.TerminalSize
import com.deepstudent.features.terminal.api.TerminalStyle
import com.deepstudent.features.terminal.api.TerminalSessions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TerminalFakeUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val host = TerminalTestHost()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun unicodeInputInterruptAndPageCloseUseOnlyTypedApi() {
        val fake = FakeSessions()
        var pageClosed = false
        host.setContent {
            DstuTheme {
                TerminalRoute(fake, fake, onClosePage = { pageClosed = true })
            }
        }
        compose.onNodeWithContentDescription("新建会话").performClick()
        compose.onNodeWithTag("terminal-input").performTextInput("中文😀")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.onNodeWithContentDescription("中断").performClick()
        compose.onNodeWithContentDescription("关闭终端").performClick()
        compose.runOnIdle {
            assertTrue(pageClosed)
            assertEquals("中文😀\n", fake.session.written)
            assertEquals(1, fake.session.interrupts)
            assertEquals(0, fake.session.closes)
        }
    }

    @Test
    fun multipleSessionsAndExitCodeAreVisibleWithoutClosingThem() {
        val fake = FakeSessions()
        host.setContent { DstuTheme { TerminalRoute(fake, fake, {}) } }
        compose.onNodeWithContentDescription("新建会话").performClick()
        compose.onNodeWithContentDescription("新建会话").performClick()
        compose.onNodeWithText("会话 2").assertIsDisplayed()
        compose.runOnIdle { fake.session.exit(37) }
        compose.onNodeWithText("已退出，退出码：37").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, fake.session.closes) }
    }

    @Test
    fun failuresNeverExposeExceptionMessages() {
        val fake = FakeSessions().apply { failOpen = true }
        host.setContent { DstuTheme { TerminalRoute(fake, fake, {}) } }
        compose.onNodeWithContentDescription("新建会话").performClick()
        compose.onNodeWithText("终端操作失败").assertIsDisplayed()
        compose.onNodeWithText("/private/user-command").assertDoesNotExist()
    }

    private class FakeSessions : TerminalSessions, TerminalQuery, TerminalCommands {
        val session = FakeSession()
        override val sessions = MutableStateFlow<List<TerminalSessionSummary>>(emptyList())
        var failOpen = false

        override suspend fun open(size: TerminalSize): TerminalSessionId {
            if (failOpen) throw IllegalStateException("/private/user-command")
            session.state.value = session.state.value.copy(phase = TerminalPhase.RUNNING)
            sessions.value = listOf(TerminalSessionSummary(session.id, sessions.value.size + 1))
            return session.id
        }

        override fun observe(id: TerminalSessionId): StateFlow<TerminalSessionSnapshot>? =
            if (sessions.value.any { it.id == id }) session.state else null

        override suspend fun write(id: TerminalSessionId, text: String) {
            session.written += text
        }

        override suspend fun interrupt(id: TerminalSessionId) {
            session.interrupts++
        }

        override suspend fun resize(id: TerminalSessionId, size: TerminalSize) = Unit

        override suspend fun terminate(id: TerminalSessionId) {
            session.state.value = session.state.value.copy(phase = TerminalPhase.EXITED, exitCode = 143)
        }

        override suspend fun close(id: TerminalSessionId) {
            session.closes++
            sessions.value = sessions.value.filterNot { it.id == id }
        }
    }

    private class FakeSession {
        val id = TerminalSessionId("fake")
        val state = MutableStateFlow(TerminalSessionSnapshot(TerminalPhase.RUNNING, screen("READY")))
        var written = ""
        var interrupts = 0
        var closes = 0

        fun exit(code: Int) {
            state.value = state.value.copy(phase = TerminalPhase.EXITED, exitCode = code)
        }

        private fun screen(text: String): TerminalScreen {
            val style = TerminalStyle(-1, -16_777_216)
            val rows = text.split('\n')
            return TerminalScreen(
                size = TerminalSize(40, 8),
                lines = List(8) { index ->
                    val row = rows.getOrElse(index) { "" }
                    List(40) { column -> TerminalCell(row.getOrElse(column) { ' ' }.toString(), style) }
                },
                historyRows = 0,
                cursorColumn = 0,
                cursorRow = 0,
                cursorVisible = true,
            )
        }
    }
}
