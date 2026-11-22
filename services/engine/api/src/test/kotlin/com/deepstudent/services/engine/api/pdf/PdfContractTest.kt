package com.deepstudent.services.engine.api.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class PdfContractTest {
    @Test fun bitmapIsAnArgbSnapshotWithDefensiveCopies() {
        val input = intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt())
        val bitmap = PdfBitmap(PdfRasterSize(2, 1), input)
        input[0] = 0
        val firstRead = bitmap.copyPixels()
        assertArrayEquals(intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt()), firstRead)
        firstRead[1] = 0
        assertArrayEquals(intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt()), bitmap.copyPixels())
    }

    @Test fun dimensionsAndPayloadAreCheckedBeforeAllocationOrCopy() {
        assertEquals(4_194_304, PdfRasterSize(2048, 2048).pixelCount)
        assertEquals(16_384, PdfRasterSize(16_384, 1).pixelCount)
        assertFailure(PdfFailureCode.INVALID_ARGUMENT) { PdfRasterSize(0, 1) }
        assertFailure(PdfFailureCode.INVALID_ARGUMENT) { PdfRasterSize(1, -1) }
        assertFailure(PdfFailureCode.LIMIT) { PdfRasterSize(2049, 2048) }
        assertFailure(PdfFailureCode.LIMIT) { PdfRasterSize(Int.MAX_VALUE, Int.MAX_VALUE) }
        assertFailure(PdfFailureCode.LIMIT) { PdfRasterSize(16_385, 1) }
        assertFailure(PdfFailureCode.INVALID_ARGUMENT) { PdfBitmap(PdfRasterSize(2, 1), IntArray(1)) }
    }

    @Test fun textPreservesGlyphOrderUnicodeAndImmutableBounds() {
        val bounds = PdfBounds(1.0, 2.0, 3.0, 4.0)
        val glyphs = mutableListOf(PdfGlyph('A'.code, bounds), PdfGlyph(0x1f642, null))
        val result = PdfPageText(glyphs)
        glyphs.clear()
        assertEquals("A\uD83D\uDE42", result.text)
        assertEquals(bounds, result.glyphs[0].bounds)
        assertNull(result.glyphs[1].bounds)
        try {
            (result.glyphs as MutableList<PdfGlyph>).clear()
            fail("glyph snapshot was mutable")
        } catch (_: UnsupportedOperationException) { }
        assertEquals("\u0000", PdfPageText(listOf(PdfGlyph(0, null))).text)
        assertFailure(PdfFailureCode.INVALID_ARGUMENT) { PdfGlyph(0xd800, null) }
        assertFailure(PdfFailureCode.INVALID_ARGUMENT) { PdfGlyph(0x110000, null) }
    }

    private fun assertFailure(expected: PdfFailureCode, action: () -> Unit) {
        try {
            action()
            fail("expected $expected")
        } catch (error: PdfFailure) {
            assertEquals(expected, error.code)
        }
    }
}
