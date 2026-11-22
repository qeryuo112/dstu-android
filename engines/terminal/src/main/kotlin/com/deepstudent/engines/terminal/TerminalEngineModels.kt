package com.deepstudent.engines.terminal

data class EngineTerminalSize(
    val columns: Int,
    val rows: Int,
) {
    init {
        require(columns in 2..400) { "Terminal columns out of range" }
        require(rows in 1..200) { "Terminal rows out of range" }
    }
}

data class EngineTerminalStyle(
    val foreground: Int,
    val background: Int,
    val flags: Int,
)

data class EngineTerminalCell(
    val text: String,
    val style: EngineTerminalStyle,
    val continuation: Boolean,
) {
    override fun toString(): String =
        "EngineTerminalCell(style=$style, continuation=$continuation)"
}

data class EngineTerminalScreen(
    val size: EngineTerminalSize,
    val lines: List<List<EngineTerminalCell>>,
    val historyRows: Int,
    val cursorColumn: Int,
    val cursorRow: Int,
    val cursorVisible: Boolean,
) {
    override fun toString(): String =
        "EngineTerminalScreen(size=$size, historyRows=$historyRows, cursor=$cursorColumn,$cursorRow, visible=$cursorVisible)"
}
