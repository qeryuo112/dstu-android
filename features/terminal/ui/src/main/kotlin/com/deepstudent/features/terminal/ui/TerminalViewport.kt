package com.deepstudent.features.terminal.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.deepstudent.features.terminal.api.TerminalScreen
import com.deepstudent.features.terminal.api.TerminalSize

@Composable
internal fun TerminalViewport(
    screen: TerminalScreen,
    modifier: Modifier,
    onResize: (TerminalSize) -> Unit,
) {
    val density = LocalDensity.current
    val paint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = with(density) { 14.dp.toPx() }
        }
    }
    val cellWidth = remember(paint) { paint.measureText("M") }
    val cellHeight = remember(paint) { paint.fontMetrics.run { descent - ascent } }
    val scroll = rememberScrollState()
    LaunchedEffect(screen.lines.size) { scroll.scrollTo(scroll.maxValue) }
    val description = remember(screen.lines) {
        screen.lines.joinToString("\n") { row -> row.joinToString("") { it.text }.trimEnd() }.trimEnd()
    }
    Box(
        modifier
            .semantics { contentDescription = description }
            .drawBehind { drawRect(Color.Black) }
            .onSizeChanged {
                val columns = (it.width / cellWidth).toInt().coerceIn(2, 400)
                val rows = (it.height / cellHeight).toInt().coerceIn(1, 200)
                onResize(TerminalSize(columns, rows))
            }
            .verticalScroll(scroll),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(with(density) { (screen.lines.size * cellHeight).toDp() }),
        ) {
            drawTerminal(screen, paint, cellWidth, cellHeight)
        }
    }
}
