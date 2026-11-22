package com.deepstudent.engines.terminal

import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator
import com.ai.assistance.operit.terminal.view.domain.ansi.TerminalChar

/**
 * Adapter over the unmodified Operit ANSI emulator. It owns one mutable screen; callers must not
 * share it across sessions. Snapshots are immutable and safe to hand to the UI.
 */
class OperitTerminalScreen(initial: EngineTerminalSize) {
    private var size = initial
    private val emulator = AnsiTerminalEmulator(initial.columns, initial.rows, HISTORY_ROWS)
    private val stream = TerminalStream(::feed)

    fun append(text: String) = stream.append(text)

    fun resize(value: EngineTerminalSize) {
        emulator.resize(value.columns, value.rows)
        size = value
    }

    fun snapshot(): EngineTerminalScreen = EngineTerminalScreen(
        size = size,
        lines = emulator.getFullContent().map(::mapLine),
        historyRows = emulator.getHistorySize(),
        cursorColumn = emulator.getCursorX(),
        cursorRow = emulator.getCursorY(),
        cursorVisible = emulator.isCursorVisible(),
    )

    private fun feed(token: String) {
        if (isControl(token[0])) {
            emulator.parse(token)
            return
        }
        val safe = if (token.length == 1 && Character.isSurrogate(token[0])) "\ufffd" else token
        val wide = TerminalWidth.isWide(safe.codePointAt(0))
        if (wide && emulator.getCursorX() == size.columns - 1) {
            emulator.parse(" \u001b[0K\r\n")
        }
        emulator.parse(if (wide && safe.length == 1) "$safe " else safe)
    }

    private fun isControl(char: Char): Boolean =
        char == '\u001b' || char.code < 32 || char.code == 127

    private fun mapLine(line: Array<TerminalChar>): List<EngineTerminalCell> {
        val result = ArrayList<EngineTerminalCell>(line.size)
        var index = 0
        while (index < line.size) {
            val cell = line[index]
            val pair = isSurrogatePair(line, index)
            result.add(EngineTerminalCell(cellText(line, index, pair), style(cell), false))
            if ((pair || TerminalWidth.isWide(cell.char.code)) && index + 1 < line.size) {
                result.add(EngineTerminalCell("", style(line[index + 1]), true))
                index++
            }
            index++
        }
        return result
    }

    private fun isSurrogatePair(line: Array<TerminalChar>, index: Int): Boolean =
        Character.isHighSurrogate(line[index].char) &&
            index + 1 < line.size &&
            Character.isLowSurrogate(line[index + 1].char)

    private fun cellText(line: Array<TerminalChar>, index: Int, pair: Boolean): String = when {
        pair -> "${line[index].char}${line[index + 1].char}"
        Character.isSurrogate(line[index].char) -> "\ufffd"
        else -> line[index].char.toString()
    }

    private fun style(cell: TerminalChar): EngineTerminalStyle {
        var flags = 0
        if (cell.isBold) flags = flags or Flags.BOLD
        if (cell.isDim) flags = flags or Flags.DIM
        if (cell.isItalic) flags = flags or Flags.ITALIC
        if (cell.isUnderline) flags = flags or Flags.UNDERLINE
        if (cell.isBlinking) flags = flags or Flags.BLINK
        if (cell.isInverse) flags = flags or Flags.INVERSE
        if (cell.isHidden) flags = flags or Flags.HIDDEN
        if (cell.isStrikethrough) flags = flags or Flags.STRIKETHROUGH
        return EngineTerminalStyle(cell.fgColor, cell.bgColor, flags)
    }

    private object Flags {
        const val BOLD = 1
        const val DIM = 2
        const val ITALIC = 4
        const val UNDERLINE = 8
        const val BLINK = 16
        const val INVERSE = 32
        const val HIDDEN = 64
        const val STRIKETHROUGH = 128
    }

    private companion object {
        const val HISTORY_ROWS = 200
    }
}
