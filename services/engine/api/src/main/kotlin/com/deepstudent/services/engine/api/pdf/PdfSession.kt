package com.deepstudent.services.engine.api.pdf

import java.io.Closeable

/**
 * Owns one open document. All operations are synchronous; callers must dispatch
 * them off the UI thread. Page indexes are zero-based. Implementations serialize
 * operations with [close]; close is idempotent and later operations fail with CLOSED.
 */
interface PdfSession : Closeable {
    fun pageCount(): Int

    fun pageInfo(page: Int): PdfPageInfo

    fun text(page: Int): PdfPageText

    /** Renders the complete page on a white background, with its intrinsic rotation. */
    fun render(page: Int, size: PdfRasterSize): PdfBitmap

    /** Maps PDF user-space points to the top-left-origin pixels of the same rendering. */
    fun pageToPixel(page: Int, size: PdfRasterSize, point: PdfPoint): PdfPoint
}

/** Width and height are rotation-aware and measured in PDF points. */
data class PdfPageInfo(val widthPoints: Float, val heightPoints: Float, val rotationDegrees: Int)

/** Coordinates use PDF user space in points, before display rotation. */
data class PdfBounds(val left: Double, val bottom: Double, val right: Double, val top: Double)

data class PdfPoint(val x: Double, val y: Double)

/** A Unicode scalar and its optional PDF user-space character box. */
data class PdfGlyph(val codePoint: Int, val bounds: PdfBounds?) {
    init {
        if (codePoint !in 0..0x10ffff || codePoint in 0xd800..0xdfff) {
            throw PdfFailure(PdfFailureCode.INVALID_ARGUMENT)
        }
    }
}

/** PDFium order, including generated whitespace; unmappable characters use U+FFFD. */
class PdfPageText(glyphs: List<PdfGlyph>) {
    val glyphs: List<PdfGlyph> = java.util.Collections.unmodifiableList(ArrayList(glyphs))
    val text: String = buildString { this@PdfPageText.glyphs.forEach { appendCodePoint(it.codePoint) } }
}
