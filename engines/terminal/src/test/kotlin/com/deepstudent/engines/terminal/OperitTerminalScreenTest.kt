package com.deepstudent.engines.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperitTerminalScreenTest {
    @Test
    fun splitSequencesMatchWholeSequences() {
        val text = "one\u001b[31mred\u001b[0m\r\n\u001b[2;3H!\u001b]0;private\u0007"
        val whole = OperitTerminalScreen(EngineTerminalSize(20, 4))
        whole.append(text)
        for (split in 0..text.length) {
            val chunked = OperitTerminalScreen(EngineTerminalSize(20, 4))
            chunked.append(text.substring(0, split))
            chunked.append(text.substring(split))
            assertEquals("split=$split", whole.snapshot(), chunked.snapshot())
        }
    }

    @Test
    fun wideCellsAndSurrogatePairAreStableAcrossChunks() {
        val screen = OperitTerminalScreen(EngineTerminalSize(12, 2))
        "A中😀Z".forEach { screen.append(it.toString()) }
        val row = screen.snapshot().lines.first()
        assertEquals(listOf("A", "中", "", "😀", "", "Z"), row.take(6).map { it.text })
        assertTrue(row[2].continuation)
        assertTrue(row[4].continuation)
        assertFalse(row[5].continuation)
        assertEquals(6, screen.snapshot().cursorColumn)
        row.forEach { cell ->
            for (index in cell.text.indices) {
                val char = cell.text[index]
                if (Character.isHighSurrogate(char)) {
                    assertTrue(index + 1 < cell.text.length && Character.isLowSurrogate(cell.text[index + 1]))
                }
                if (Character.isLowSurrogate(char)) {
                    assertTrue(index > 0 && Character.isHighSurrogate(cell.text[index - 1]))
                }
            }
        }
    }

    @Test
    fun snapshotsDoNotChangeWhenEmulatorChanges() {
        val screen = OperitTerminalScreen(EngineTerminalSize(10, 2))
        screen.append("first")
        val old = screen.snapshot()
        screen.append("\rnext")
        assertEquals("first", old.lines.first().take(5).joinToString("") { it.text })
    }

    @Test
    fun overwriteAndMalformedInputNeverExposeIsolatedSurrogates() {
        val screen = OperitTerminalScreen(EngineTerminalSize(12, 2))
        screen.append("😀\rX\udc00!")
        val text = screen.snapshot().lines.first().joinToString("") { it.text }
        assertEquals("X\ufffd!", text.take(3))
        assertTrue(text.none { Character.isSurrogate(it) })
    }

    @Test
    fun wideCharacterWrapsBeforeLastColumn() {
        val screen = OperitTerminalScreen(EngineTerminalSize(4, 2))
        screen.append("abcd\u001b[1;4H中")
        val snapshot = screen.snapshot()
        assertEquals("abc ", snapshot.lines[0].joinToString("") { it.text })
        assertEquals("中", snapshot.lines[1][0].text)
        assertTrue(snapshot.lines[1][1].continuation)
        assertEquals(2, snapshot.cursorColumn)
    }

    @Test
    fun overlongEscapeFailsInsteadOfGrowingForever() {
        val screen = OperitTerminalScreen(EngineTerminalSize(10, 2))
        try {
            screen.append("\u001b]0;" + "x".repeat(8192))
            throw AssertionError("Expected bounded sequence failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("limit"))
        }
    }
}
