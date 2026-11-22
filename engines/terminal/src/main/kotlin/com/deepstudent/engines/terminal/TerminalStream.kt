package com.deepstudent.engines.terminal

/**
 * Frames complete UTF-16 code points and escape sequences across InputStream boundaries.
 * Interpretation remains entirely in Operit's mature ANSI scanner/emulator.
 */
internal class TerminalStream(
    private val accept: (String) -> Unit,
) {
    private val pending = StringBuilder()

    fun append(text: String) {
        pending.append(text)
        var offset = 0
        while (offset < pending.length) {
            val end = tokenEnd(offset)
            if (end < 0) break
            accept(pending.substring(offset, end))
            offset = end
        }
        pending.delete(0, offset)
        require(pending.length <= MAX_SEQUENCE) { "Terminal escape sequence exceeds limit" }
    }

    private fun tokenEnd(start: Int): Int {
        if (pending[start] != ESC) return characterEnd(start)
        if (start + 1 >= pending.length) return -1
        return when (pending[start + 1]) {
            '[' -> csiEnd(start + 2)
            ']', 'P' -> stringEnd(start + 2, pending[start + 1] == ']')
            else -> start + 2
        }
    }

    private fun characterEnd(start: Int): Int {
        if (!Character.isHighSurrogate(pending[start])) return start + 1
        if (start + 1 == pending.length) return -1
        return start + if (Character.isLowSurrogate(pending[start + 1])) 2 else 1
    }

    private fun csiEnd(start: Int): Int {
        for (index in start until pending.length) {
            if (pending[index].code in 0x40..0x7e) return index + 1
        }
        return -1
    }

    private fun stringEnd(start: Int, bell: Boolean): Int {
        for (index in start until pending.length) {
            if (bell && pending[index] == '\u0007') return index + 1
            if (pending[index] == ESC && index + 1 < pending.length && pending[index + 1] == '\\') {
                return index + 2
            }
        }
        return -1
    }

    private companion object {
        private const val ESC = '\u001b'
        private const val MAX_SEQUENCE = 8192
    }
}
