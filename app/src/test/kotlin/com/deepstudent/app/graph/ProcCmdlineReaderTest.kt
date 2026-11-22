package com.deepstudent.app.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcCmdlineReaderTest {
    @Test
    fun `parses null terminated process name and ignores trailing args`() {
        val bytes = "com.deepstudent.app:engine\u0000--flag\u0000".toByteArray()

        assertEquals(
            "com.deepstudent.app:engine",
            ProcCmdlineReader.parse(bytes, bytes.size),
        )
    }

    @Test
    fun `parses process name without null terminator`() {
        val bytes = "com.deepstudent.app".toByteArray()

        assertEquals("com.deepstudent.app", ProcCmdlineReader.parse(bytes, bytes.size))
    }

    @Test
    fun `trims whitespace`() {
        val bytes = "  com.deepstudent.app:isolated  ".toByteArray()

        assertEquals(
            "com.deepstudent.app:isolated",
            ProcCmdlineReader.parse(bytes, bytes.size),
        )
    }

    @Test
    fun `rejects empty and zero length input`() {
        assertNull(ProcCmdlineReader.parse(ByteArray(0), 0))
        assertNull(ProcCmdlineReader.parse(byteArrayOf(0), 1))
        assertNull(ProcCmdlineReader.parse("   ".toByteArray(), 3))
    }

    @Test
    fun `respects explicit read length`() {
        val bytes = "main\u0000ignored".toByteArray()

        assertEquals("main", ProcCmdlineReader.parse(bytes, 4))
    }
}
