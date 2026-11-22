package com.deepstudent.engines.pdf

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.Closeable

/** A seekable PFD is borrowed; the engine duplicates it and never closes the caller's PFD. */
object PdfEngine {
    fun open(source: ParcelFileDescriptor, password: String? = null): PdfDocument {
        require(password == null || !password.contains('\u0000')) { "Password contains NUL" }
        val secret = password?.toByteArray(Charsets.UTF_8)
        val id = try { PdfNative.open(source.fd, secret) } finally { secret?.fill(0) }
        if (id <= 0) {
            val error = PdfError.fromNative(id.toInt())
            Log.w("DstuPdf", "open failed error=$error")
            throw PdfException(error)
        }
        Log.d("DstuPdf", "document opened")
        return PdfDocument(id)
    }
}

/** Character boxes use PDF user space in points. Page size is rotation-aware. */
data class PdfRect(val left: Double, val bottom: Double, val right: Double, val top: Double)
data class PdfPoint(val x: Double, val y: Double)
data class PdfPageInfo(val widthPoints: Float, val heightPoints: Float, val rotationDegrees: Int)
data class PdfCharacter(val codePoint: Int, val bounds: PdfRect?)
/** PDFium character order, including generated whitespace; unmappable characters become U+FFFD. */
data class PdfText(val characters: List<PdfCharacter>) {
    val text: String get() = buildString { characters.forEach { appendCodePoint(it.codePoint) } }
}

enum class PdfError {
    UNKNOWN, FILE, FORMAT, PASSWORD, SECURITY, PAGE, LIMIT;

    companion object {
        internal fun fromNative(code: Int): PdfError = when (code) {
            -2, -100 -> FILE
            -3 -> FORMAT
            -4 -> PASSWORD
            -5 -> SECURITY
            -6 -> PAGE
            -101, -102 -> LIMIT
            else -> UNKNOWN
        }
    }
}

class PdfException(val error: PdfError) : IllegalStateException("PDF: ${error.name}")

/** Owns one document; operations and close are serialized. Page indexes start at zero. */
class PdfDocument internal constructor(private var id: Long) : Closeable {
    @Synchronized
    fun pageCount(): Int = PdfNative.pageCount(openId())

    @Synchronized
    fun pageInfo(page: Int): PdfPageInfo {
        val info = PdfNative.pageInfo(openId(), page)
        return PdfPageInfo(info[0], info[1], info[2].toInt() * 90)
    }

    @Synchronized
    fun text(page: Int): PdfText = decodeCharacters(PdfNative.characters(openId(), page))

    /** Renders the full page into an existing mutable ARGB_8888 bitmap on a white background. */
    @Synchronized
    fun render(page: Int, bitmap: Bitmap) {
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888)
        require(bitmap.width.toLong() * bitmap.height <= MAX_PIXELS) { "Bitmap exceeds PDF budget" }
        PdfNative.render(openId(), page, bitmap)
    }

    /** Uses the same full-page rotation and pixel dimensions as [render]. */
    @Synchronized
    fun pageToPixel(page: Int, width: Int, height: Int, point: PdfPoint): PdfPoint {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS)
        require(point.x.isFinite() && point.y.isFinite())
        val result = PdfNative.pageToPixel(openId(), page, width, height, point.x, point.y)
        return PdfPoint(result[0], result[1])
    }

    @Synchronized
    override fun close() {
        if (id != 0L) {
            PdfNative.close(id)
            Log.d("DstuPdf", "document closed")
        }
        id = 0L
    }

    private fun openId(): Long = id.takeIf { it != 0L } ?: error("PDF document is closed")

    companion object {
        const val MAX_PIXELS: Long = 16_777_216
    }
}
