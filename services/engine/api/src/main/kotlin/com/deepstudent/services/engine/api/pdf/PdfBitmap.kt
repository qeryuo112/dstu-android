package com.deepstudent.services.engine.api.pdf

/**
 * Limits a single render to 4,194,304 pixels and at most 16,384 pixels per side.
 * Implementations and callers may temporarily hold multiple 4-byte copies.
 */
data class PdfRasterSize(val width: Int, val height: Int) {
    init {
        if (width <= 0 || height <= 0) throw PdfFailure(PdfFailureCode.INVALID_ARGUMENT)
        if (width > MAX_DIMENSION || height > MAX_DIMENSION || width.toLong() * height > MAX_PIXELS) {
            throw PdfFailure(PdfFailureCode.LIMIT)
        }
    }

    val pixelCount: Int get() = width * height

    companion object {
        const val MAX_DIMENSION: Int = 16_384
        const val MAX_PIXELS: Long = 4_194_304
    }
}

/**
 * An immutable top-to-bottom, row-major snapshot of 0xAARRGGBB pixels.
 * Both the constructor input and [copyPixels] output are defensive copies.
 */
class PdfBitmap(val size: PdfRasterSize, pixels: IntArray) {
    private val pixels: IntArray

    init {
        if (pixels.size != size.pixelCount) throw PdfFailure(PdfFailureCode.INVALID_ARGUMENT)
        this.pixels = pixels.copyOf()
    }

    fun copyPixels(): IntArray = pixels.copyOf()
}
