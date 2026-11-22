package com.deepstudent.engines.pdf

import android.graphics.Bitmap
import android.util.Log

internal object PdfNative {
    init { System.loadLibrary("dstu_pdf") }

    @JvmStatic
    fun raise(code: Int) {
        val error = PdfError.fromNative(code)
        Log.w("DstuPdf", "native operation failed error=$error")
        throw PdfException(error)
    }

    external fun open(fd: Int, password: ByteArray?): Long
    external fun close(id: Long)
    external fun pageCount(id: Long): Int
    external fun pageInfo(id: Long, page: Int): FloatArray
    external fun characters(id: Long, page: Int): DoubleArray
    external fun render(id: Long, page: Int, bitmap: Bitmap)
    external fun pageToPixel(id: Long, page: Int, width: Int, height: Int, x: Double, y: Double): DoubleArray
}

internal fun decodeCharacters(data: DoubleArray): PdfText {
    check(data.size % 5 == 0)
    return PdfText(List(data.size / 5) { index ->
        val i = index * 5
        val value = data[i].toInt()
        val codePoint = if (value in 1..0x10ffff && value !in 0xd800..0xdfff) value else 0xfffd
        val bounds = if (data[i + 1].isNaN()) null else PdfRect(
            data[i + 1], data[i + 2], data[i + 3], data[i + 4],
        )
        PdfCharacter(codePoint, bounds)
    })
}
