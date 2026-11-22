package com.deepstudent.app.terminal

import androidx.compose.runtime.Composable
import com.deepstudent.features.terminal.api.TerminalSessions
import com.deepstudent.features.terminal.ui.TerminalRoute

@Composable
internal fun TerminalPanel(
    sessions: TerminalSessions,
    onClosePanel: () -> Unit,
) {
    TerminalRoute(
        query = sessions,
        commands = sessions,
        onClosePage = onClosePanel,
    )
}
