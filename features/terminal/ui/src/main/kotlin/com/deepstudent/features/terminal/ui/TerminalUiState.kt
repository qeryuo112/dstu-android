package com.deepstudent.features.terminal.ui

import com.deepstudent.features.terminal.api.TerminalSessionId
import com.deepstudent.features.terminal.api.TerminalSessionSnapshot
import com.deepstudent.features.terminal.api.TerminalSessionSummary
import com.deepstudent.features.terminal.api.TerminalSize

enum class TerminalUiFailure {
    START_FAILED,
    IO_FAILED,
    RESIZE_FAILED,
    STOP_FAILED,
    SESSION_CLOSED,
    UNKNOWN,
}

data class TerminalUiState(
    val sessions: List<TerminalSessionSummary> = emptyList(),
    val selectedId: TerminalSessionId? = null,
    val snapshot: TerminalSessionSnapshot? = null,
    val failure: TerminalUiFailure? = null,
    val busy: Boolean = false,
)

data class TerminalActions(
    val create: () -> Unit,
    val select: (TerminalSessionId) -> Unit,
    val write: (String) -> Unit,
    val interrupt: () -> Unit,
    val resize: (TerminalSize) -> Unit,
    val terminate: () -> Unit,
    val close: (TerminalSessionId) -> Unit,
    val dismissFailure: () -> Unit,
)
