package com.anchorreader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import com.anchorreader.data.Book
import com.anchorreader.ui.Palette
import com.anchorreader.ui.SourceDiagram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class RenderedPage(val bitmap: Bitmap? = null, val count: Int = 0, val error: String? = null)

@Composable
fun SourceDialog(book: Book, initialPage: Int, onClose: () -> Unit) {
    if (!book.isSample && book.pdfPath == null) {
        TextSourceDialog(book, onClose)
        return
    }
    var page by rememberSaveablePage(book.id, initialPage)
    var zoom by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var figureSize by remember { mutableStateOf(IntSize.Zero) }
    fun setZoom(value: Float) {
        val next = value.coerceIn(1f, 4f)
        val ratio = next / zoom
        offsetX = (offsetX * ratio).coerceIn(-figureSize.width * (next - 1f), 0f)
        offsetY = (offsetY * ratio).coerceIn(-figureSize.height * (next - 1f), 0f)
        zoom = next
    }
    val rendered by produceState(RenderedPage(), book.pdfPath, page) {
        if (book.pdfPath != null) value = withContext(Dispatchers.IO) {
            try {
                ParcelFileDescriptor.open(File(book.pdfPath), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(page.coerceIn(0, renderer.pageCount - 1)).use { source ->
                            val width = 1200
                            val height = (width.toLong() * source.height / source.width).coerceIn(1, 2400).toInt()
                            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            image.eraseColor(android.graphics.Color.WHITE)
                            source.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            RenderedPage(image, renderer.pageCount)
                        }
                    }
                }
            } catch (_: Exception) { RenderedPage(error = "Could not display this source page. Full text remains available.") }
        }
    }
    ReaderDialog(onClose) {
        Surface(Modifier.fillMaxSize(), color = Palette.Canvas) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (book.isSample) "SOURCE FIGURE" else "SOURCE", color = Palette.Teal, fontSize = 12.sp)
                        Text(if (book.isSample) "One request. Three parts." else book.title, fontFamily = FontFamily.Serif, fontSize = 25.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close source") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    if (book.isSample || book.pdfPath != null) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 220.dp).clipToBounds().pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val touches = event.changes.count { it.pressed }
                                    // At 1×, one finger scrolls the surrounding source view.
                                    if (touches > 0 && (zoom > 1f || touches > 1)) {
                                        val center = event.calculateCentroid(useCurrent = false)
                                        if (center.x.isFinite() && center.y.isFinite()) {
                                            val pan = event.calculatePan()
                                            val next = (zoom * event.calculateZoom()).coerceIn(1f, 4f)
                                            val ratio = next / zoom
                                            offsetX = (center.x - (center.x - offsetX) * ratio + pan.x).coerceIn(-figureSize.width * (next - 1f), 0f)
                                            offsetY = (center.y - (center.y - offsetY) * ratio + pan.y).coerceIn(-figureSize.height * (next - 1f), 0f)
                                            zoom = next
                                            event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }) {
                            val figureModifier = Modifier.fillMaxWidth().onSizeChanged { figureSize = it }.graphicsLayer { transformOrigin = TransformOrigin(0f, 0f); scaleX = zoom; scaleY = zoom; translationX = offsetX; translationY = offsetY; clip = true }
                            if (book.isSample) SourceDiagram(figureModifier.aspectRatio(640f / 360f))
                            else if (rendered.bitmap != null) Image(rendered.bitmap!!.asImageBitmap(), "Source PDF page ${page + 1}", modifier = figureModifier)
                            else if (rendered.error != null) Text(rendered.error!!)
                            else CircularProgressIndicator(Modifier.align(Alignment.Center))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { setZoom(zoom / 1.5f) }, modifier = Modifier.weight(1f)) { Text("Zoom out") }
                            TextButton(onClick = { setZoom(zoom * 1.5f) }, modifier = Modifier.weight(1f)) { Text("Zoom in") }
                            TextButton(onClick = { zoom = 1f; offsetX = 0f; offsetY = 0f }, modifier = Modifier.weight(1f)) { Text("Reset view") }
                        }
                    }
                    if (book.pdfPath != null && rendered.count > 0) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { page--; zoom = 1f; offsetX = 0f; offsetY = 0f }, modifier = Modifier.weight(1f), enabled = page > 0) { Text("Previous page") }
                            Text("${page + 1} / ${rendered.count}")
                            TextButton(onClick = { page++; zoom = 1f; offsetX = 0f; offsetY = 0f }, modifier = Modifier.weight(1f), enabled = page + 1 < rendered.count) { Text("Next page") }
                        }
                    }
                    if (book.isSample) {
                        Text("Read from left to right: browser request → service worker → stored response. The return path sends the cached response back to the browser.", color = Palette.Muted)
                        Text("Original AnchorReader demo diagram. Source: How offline web caching works, prepared example by AnchorReader.", color = Palette.Muted, fontSize = 13.sp)
                    } else if (book.pdfPath == null) SelectionContainer { Text(book.sourceText, lineHeight = 25.sp) }
                    if (book.author.isNotBlank()) Text("Author: ${book.author}", color = Palette.Muted)
                    Text("Keep only material you own or have permission to use.", color = Palette.Muted, fontSize = 12.sp)
                }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Back to lesson") }
            }
        }
    }
}

@Composable
private fun rememberSaveablePage(id: String, initialPage: Int) = androidx.compose.runtime.saveable.rememberSaveable(id) { mutableIntStateOf(initialPage.coerceAtLeast(0)) }

@Composable
private fun TextSourceDialog(book: Book, onClose: () -> Unit) {
    val sections = remember(book.id) { book.sourceText.chunked(8000) }
    ReaderDialog(onClose) {
        Surface(Modifier.fillMaxSize(), color = Palette.Canvas) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(book.title, Modifier.weight(1f), fontFamily = FontFamily.Serif, fontSize = 25.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close source") }
                }
                Text("Original book text", color = Palette.Teal, fontSize = 12.sp)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sections) { section -> SelectionContainer { Text(section, lineHeight = 25.sp) } }
                }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Back to lesson") }
            }
        }
    }
}
