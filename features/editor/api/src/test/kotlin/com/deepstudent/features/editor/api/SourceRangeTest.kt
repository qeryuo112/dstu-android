package com.deepstudent.features.editor.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceRangeTest {
    @Test
    fun `ranges use UTF-16 and retain combining and joined characters`() {
        val source = "中👩‍💻é\r\nX"
        assertEquals("👩‍💻", SourceRange(1, 6).textOf(source))
        assertEquals("é", SourceRange(6, 8).textOf(source))
        assertFalse(isTextBoundary(source, 2))
        assertTrue(isTextBoundary(source, 3))
        assertFalse(isTextBoundary(source, 9))
        assertThrows(IllegalArgumentException::class.java) { SourceRange(2, 6).textOf(source) }
        assertThrows(IllegalArgumentException::class.java) { SourceRange(8, 9).textOf(source) }
    }

    @Test
    fun `invalid ranges cannot be constructed or read`() {
        assertThrows(IllegalArgumentException::class.java) { SourceRange(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SourceRange(3, 2) }
        assertThrows(IllegalArgumentException::class.java) { SourceRange(0, 3).textOf("ab") }
    }
}
