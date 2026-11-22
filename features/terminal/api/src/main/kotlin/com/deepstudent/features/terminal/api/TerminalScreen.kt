package com.deepstudent.features.terminal.api

object TerminalTextFlags {
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val BLINK = 16
    const val INVERSE = 32
    const val HIDDEN = 64
    const val STRIKETHROUGH = 128
}

data class TerminalStyle(
    val foreground: Int,
    val background: Int,
    val flags: Int = 0,
)

data class TerminalCell(
    val text: String,
    val style: TerminalStyle,
    val continuation: Boolean = false,
) {
    override fun toString(): String =
        "TerminalCell(style=$style, continuation=$continuation)"
}

data class TerminalScreen(
    val size: TerminalSize,
    val lines: List<List<TerminalCell>>,
    val historyRows: Int,
    val cursorColumn: Int,
    val cursorRow: Int,
    val cursorVisible: Boolean,
) {
    override fun toString(): String =
        "TerminalScreen(size=$size, historyRows=$historyRows, cursor=$cursorColumn,$cursorRow, visible=$cursorVisible)"
}

enum class TerminalPhase {
    STARTING,
    RUNNING,
    STOPPING,
    EXITED,
    FAILED,
}

enum class TerminalFailure {
    START_FAILED,
    IO_FAILED,
    RESIZE_FAILED,
    STOP_FAILED,
    EXIT_STATUS_UNAVAILABLE,
    SESSION_CLOSED,
}

data class TerminalSessionSnapshot(
    val phase: TerminalPhase,
    val screen: TerminalScreen,
    val exitCode: Int? = null,
    val failure: TerminalFailure? = null,
) {
    override fun toString(): String =
        "TerminalSessionSnapshot(phase=$phase, exitCode=$exitCode, failure=$failure)"
}

data class TerminalSessionSummary(
    val id: TerminalSessionId,
    val ordinal: Int,
)

class TerminalCommandException(
    val failure: TerminalFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)
