package com.deepstudent.features.terminal.ui

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.deepstudent.features.terminal.api.TerminalCell
import com.deepstudent.features.terminal.api.TerminalScreen
import com.deepstudent.features.terminal.api.TerminalTextFlags

internal fun DrawScope.drawTerminal(
    screen: TerminalScreen,
    paint: Paint,
    cellWidth: Float,
    cellHeight: Float,
) {
    screen.lines.forEachIndexed { row, cells ->
        cells.forEachIndexed { column, cell ->
            val inverse = cell.style.flags and TerminalTextFlags.INVERSE != 0
            val background = if (inverse) cell.style.foreground else cell.style.background
            drawRect(Color(background), Offset(column * cellWidth, row * cellHeight), Size(cellWidth, cellHeight))
        }
    }
    screen.lines.forEachIndexed { row, cells ->
        cells.forEachIndexed { column, cell ->
            drawGlyph(cell, paint, column * cellWidth, row * cellHeight)
        }
    }
    if (screen.cursorVisible) {
        val x = screen.cursorColumn * cellWidth
        val y = (screen.historyRows + screen.cursorRow) * cellHeight
        drawRect(Color.LightGray.copy(alpha = 0.45f), Offset(x, y), Size(cellWidth, cellHeight))
    }
}

private fun DrawScope.drawGlyph(cell: TerminalCell, paint: Paint, x: Float, y: Float) {
    val flags = cell.style.flags
    val inverse = flags and TerminalTextFlags.INVERSE != 0
    val foreground = if (inverse) cell.style.background else cell.style.foreground
    if (!cell.continuation && flags and TerminalTextFlags.HIDDEN == 0) {
        paint.color = foreground
        paint.isFakeBoldText = flags and TerminalTextFlags.BOLD != 0
        paint.isUnderlineText = flags and TerminalTextFlags.UNDERLINE != 0
        drawIntoCanvas {
            it.nativeCanvas.drawText(cell.text, x, y - paint.fontMetrics.ascent, paint)
        }
    }
}
