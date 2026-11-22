package com.deepstudent.features.terminal.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.deepstudent.features.terminal.api.TerminalCell
import com.deepstudent.features.terminal.api.TerminalScreen
import com.deepstudent.features.terminal.api.TerminalSize
import com.deepstudent.features.terminal.api.TerminalStyle
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TerminalDrawingDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val host = TerminalTestHost()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun followingBackgroundNeverErasesWideGlyphPixels() {
        val style = TerminalStyle(0xffffffff.toInt(), 0xff000000.toInt())
        val text = mutableStateOf("中")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 64f
        }
        val width = paint.measureText("M")
        val height = paint.fontMetrics.run { descent - ascent }
        host.setContent {
            Canvas(Modifier.size(180.dp, 110.dp).testTag("wide-glyph")) {
                val cells = listOf(
                    TerminalCell(text.value, style),
                    TerminalCell("", style, continuation = true),
                    TerminalCell(" ", style),
                )
                drawTerminal(
                    TerminalScreen(TerminalSize(3, 1), listOf(cells), 0, 0, 0, false),
                    paint,
                    width,
                    height,
                )
            }
        }
        val image = compose.onNodeWithTag("wide-glyph").captureToImage().toPixelMap()
        val start = width.toInt() + 1
        val end = minOf((width * 2).toInt(), image.width)
        var lit = 0
        for (x in start until end) {
            for (y in 0 until minOf(height.toInt(), image.height)) {
                val pixel = image[x, y]
                if (pixel.red + pixel.green + pixel.blue > 0.4f) lit++
            }
        }
        assertTrue("Second cell must retain the right half of the CJK glyph", lit > 5)
    }
}
