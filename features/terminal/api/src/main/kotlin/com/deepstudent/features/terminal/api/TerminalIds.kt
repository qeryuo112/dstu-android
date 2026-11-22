package com.deepstudent.features.terminal.api

import kotlin.jvm.JvmInline

@JvmInline
value class TerminalSessionId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 64) { "Invalid terminal session id" }
    }

    override fun toString(): String = "TerminalSessionId(${value.take(8)})"
}

data class TerminalSize(
    val columns: Int,
    val rows: Int,
) {
    init {
        require(columns in MIN_COLUMNS..MAX_COLUMNS) { "Terminal columns out of range" }
        require(rows in MIN_ROWS..MAX_ROWS) { "Terminal rows out of range" }
    }

    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 400
        const val MIN_ROWS = 1
        const val MAX_ROWS = 200
        val Default = TerminalSize(columns = 80, rows = 24)
    }
}
