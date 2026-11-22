package com.deepstudent.engines.pdf

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import com.deepstudent.services.engine.api.pdf.PdfBitmap
import com.deepstudent.services.engine.api.pdf.PdfBounds
import com.deepstudent.services.engine.api.pdf.PdfFailure
import com.deepstudent.services.engine.api.pdf.PdfFailureCode
import com.deepstudent.services.engine.api.pdf.PdfGlyph
import com.deepstudent.services.engine.api.pdf.PdfPageInfo as SessionPageInfo
import com.deepstudent.services.engine.api.pdf.PdfPageText
import com.deepstudent.services.engine.api.pdf.PdfPoint as SessionPoint
import com.deepstudent.services.engine.api.pdf.PdfRasterSize
import com.deepstudent.services.engine.api.pdf.PdfSession

/** The caller retains the seekable PFD; the existing engine owns its duplicated descriptor. */
object PdfSessionFactory {
    fun open(source: ParcelFileDescriptor, password: String? = null): PdfSession {
        if (password?.contains('\u0000') == true) throw PdfFailure(PdfFailureCode.INVALID_ARGUMENT)
        val document = try {
            PdfEngine.open(source, password)
        } catch (error: PdfException) {
            throw error.toFailure()
        } catch (_: IllegalStateException) {
            Log.w("DstuPdf", "session open failed error=FILE causeClass=IllegalStateException")
            throw PdfFailure(PdfFailureCode.FILE)
        }
        return try {
            PdfSessionAdapter(document)
        } catch (error: Throwable) {
            document.close()
            throw error
        }
    }
}

private class PdfSessionAdapter(private val document: PdfDocument) : PdfSession {
    private var closed = false

    @Synchronized
    override fun pageCount(): Int = read { document.pageCount() }

    @Synchronized
    override fun pageInfo(page: Int): SessionPageInfo = read {
        val info = document.pageInfo(page)
        SessionPageInfo(info.widthPoints, info.heightPoints, info.rotationDegrees)
    }

    @Synchronized
    override fun text(page: Int): PdfPageText = read {
        PdfPageText(document.text(page).characters.map { character ->
            val bounds = character.bounds?.let { PdfBounds(it.left, it.bottom, it.right, it.top) }
            PdfGlyph(character.codePoint, bounds)
        })
    }

    @Synchronized
    override fun render(page: Int, size: PdfRasterSize): PdfBitmap = read {
        val bitmap = try {
            Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        } catch (_: IllegalArgumentException) {
            Log.w("DstuPdf", "session bitmap rejected error=LIMIT causeClass=IllegalArgumentException")
            throw PdfFailure(PdfFailureCode.LIMIT)
        }
        try {
            document.render(page, bitmap)
            val pixels = IntArray(size.pixelCount)
            bitmap.getPixels(pixels, 0, size.width, 0, 0, size.width, size.height)
            PdfBitmap(size, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    @Synchronized
    override fun pageToPixel(page: Int, size: PdfRasterSize, point: SessionPoint): SessionPoint = read {
        if (!point.x.isFinite() || !point.y.isFinite()) throw PdfFailure(PdfFailureCode.INVALID_ARGUMENT)
        val mapped = document.pageToPixel(page, size.width, size.height, PdfPoint(point.x, point.y))
        SessionPoint(mapped.x, mapped.y)
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            document.close()
            closed = true
        }
    }

    private inline fun <T> read(action: () -> T): T {
        if (closed) throw PdfFailure(PdfFailureCode.CLOSED)
        return try {
            action()
        } catch (error: PdfException) {
            throw error.toFailure()
        }
    }
}

internal fun PdfException.toFailure(): PdfFailure = PdfFailure(
    when (error) {
        PdfError.UNKNOWN -> PdfFailureCode.UNKNOWN
        PdfError.FILE -> PdfFailureCode.FILE
        PdfError.FORMAT -> PdfFailureCode.FORMAT
        PdfError.PASSWORD -> PdfFailureCode.PASSWORD
        PdfError.SECURITY -> PdfFailureCode.SECURITY
        PdfError.PAGE -> PdfFailureCode.PAGE
        PdfError.LIMIT -> PdfFailureCode.LIMIT
    },
).also { Log.w("DstuPdf", "session operation failed error=${it.code} causeClass=PdfException") }
