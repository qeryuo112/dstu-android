package com.deepstudent.features.terminal.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TerminalContractsTest {
    @Test
    fun terminalSizeRejectsValuesThatCannotFitThePtyBridge() {
        listOf(1 to 24, 401 to 24, 80 to 0, 80 to 201).forEach { (columns, rows) ->
            try {
                TerminalSize(columns, rows)
                throw AssertionError("Expected invalid size $columns x $rows")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun screenAndSessionToStringNeverContainOutput() {
        val style = TerminalStyle(0xffffffff.toInt(), 0xff000000.toInt())
        val screen = TerminalScreen(
            TerminalSize(2, 1),
            listOf(listOf(TerminalCell("私", style), TerminalCell("😀", style))),
            historyRows = 0,
            cursorColumn = 1,
            cursorRow = 0,
            cursorVisible = true,
        )
        val snapshot = TerminalSessionSnapshot(TerminalPhase.RUNNING, screen)
        assertEquals("TerminalScreen(size=TerminalSize(columns=2, rows=1), historyRows=0, cursor=1,0, visible=true)", screen.toString())
        assertFalse(snapshot.toString().contains("私"))
        assertFalse(snapshot.toString().contains("😀"))
    }
}
