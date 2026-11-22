package com.deepstudent.features.documents.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import com.deepstudent.features.documents.api.PdfPages
import kotlinx.coroutines.CancellationException

private sealed interface PageState {
    data object Loading : PageState
    data object Empty : PageState
    data object Failed : PageState
    data class Ready(val image: ImageBitmap, val count: Int) : PageState
}

@Composable
fun PdfPanel(pages: PdfPages, reference: String?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    var page by remember(reference) { mutableStateOf(0) }
    val state by rememberPage(pages, reference, page)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onOpen, Modifier.testTag("pdf-open")) { Icon(Icons.Default.FolderOpen, "打开 PDF") }
            IconButton({ page-- }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, "上一页") }
            Text(if (state is PageState.Ready) "${page + 1} / ${(state as PageState.Ready).count}" else "PDF")
            IconButton({ page++ }, enabled = (state as? PageState.Ready)?.let { page + 1 < it.count } == true) {
                Icon(Icons.AutoMirrored.Filled.NavigateNext, "下一页")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { PdfPageBody(state) }
    }
}

@Composable
private fun rememberPage(pages: PdfPages, reference: String?, page: Int) =
    produceState<PageState>(PageState.Empty, pages, reference, page) {
        value = if (reference == null) PageState.Empty else {
            value = PageState.Loading
            try {
                val result = pages.load(reference, page)
                val size = result.bitmap.size
                val bitmap = Bitmap.createBitmap(result.bitmap.copyPixels(), size.width, size.height, Bitmap.Config.ARGB_8888)
                PageState.Ready(bitmap.asImageBitmap(), result.pageCount)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PageState.Failed }
        }
    }

@Composable
private fun PdfPageBody(state: PageState) {
    when (state) {
        PageState.Loading -> CircularProgressIndicator()
        PageState.Empty -> Text("未打开文档")
        PageState.Failed -> Text("无法读取文档，请重新选择")
        is PageState.Ready -> Image(state.image, "PDF 页面", Modifier.fillMaxSize().testTag("pdf-page"),
            contentScale = ContentScale.Fit)
    }
}
