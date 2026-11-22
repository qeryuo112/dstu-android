package com.deepstudent.features.documents.data

import android.content.ContentResolver
import android.net.Uri
import com.deepstudent.engines.pdf.PdfSessionFactory
import com.deepstudent.features.documents.api.PdfPageSnapshot
import com.deepstudent.features.documents.api.PdfPages
import com.deepstudent.services.engine.api.pdf.PdfRasterSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class AndroidPdfPages(private val resolver: ContentResolver) : PdfPages {
    override suspend fun load(reference: String, page: Int): PdfPageSnapshot = withContext(Dispatchers.IO) {
        val uri = Uri.parse(reference)
        require(uri.scheme == "content" && page >= 0) { "invalid document reference" }
        checkNotNull(resolver.openFileDescriptor(uri, "r")).use { descriptor ->
            PdfSessionFactory.open(descriptor).use { session ->
                val count = session.pageCount()
                require(page < count) { "invalid document page" }
                val info = session.pageInfo(page)
                val width = 1000
                val height = (width * info.heightPoints / info.widthPoints).roundToInt().coerceIn(1, 4000)
                PdfPageSnapshot(page, count, session.render(page, PdfRasterSize(width, height)))
            }
        }
    }
}
