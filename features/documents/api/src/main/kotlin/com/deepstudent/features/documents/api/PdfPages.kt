package com.deepstudent.features.documents.api

import com.deepstudent.services.engine.api.pdf.PdfBitmap

data class PdfPageSnapshot(val page: Int, val pageCount: Int, val bitmap: PdfBitmap)

fun interface PdfPages {
    suspend fun load(reference: String, page: Int): PdfPageSnapshot
}
