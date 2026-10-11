package com.anchorreader.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

internal const val PDF_COVER_MAX_SIDE = 640

private data class PdfCoverState(val bitmap: Bitmap? = null, val loading: Boolean = true)

@Composable
fun PdfCover(path: String, title: String, modifier: Modifier = Modifier) {
    val cover by produceState(PdfCoverState(), path) {
        value = PdfCoverState(renderPdfCover(File(path)), loading = false)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = cover.bitmap
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), "First PDF page for $title", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else if (cover.loading) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.PictureAsPdf, null, tint = Palette.Deep, modifier = Modifier.size(32.dp))
                Text("Cover unavailable", color = Palette.Muted, fontSize = 12.sp)
            }
        }
    }
}

/** Keeps the original first page intact, including blank pages. All PDF work runs off the UI thread. */
internal suspend fun renderPdfCover(file: File): Bitmap? = withContext(Dispatchers.IO) {
    var image: Bitmap? = null
    try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    require(page.width > 0 && page.height > 0)
                    val scale = PDF_COVER_MAX_SIDE.toDouble() / maxOf(page.width, page.height)
                    val width = (page.width * scale).roundToInt().coerceIn(1, PDF_COVER_MAX_SIDE)
                    val height = (page.height * scale).roundToInt().coerceIn(1, PDF_COVER_MAX_SIDE)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    image = bitmap
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
        image
    } catch (_: Exception) {
        image?.recycle()
        null
    }
}
