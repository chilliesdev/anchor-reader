package com.anchorreader.ui

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.min

/** Native ports of the inline SVGs in the supplied original design. */
@Composable
fun CoverArt(modifier: Modifier = Modifier) {
    Drawing(320f, 160f, "A browser page connects to a service worker and a saved cache.", modifier) {
        rect(18f, 30f, 78f, 82f, 10f, 0xFFFFFFFF.toInt(), 0xFF78999C.toInt(), 2f)
        circle(32f, 44f, 3f, 0xFFC4D84E.toInt()); circle(43f, 44f, 3f, 0xFF9BB9BC.toInt())
        lines(0xFF75969A.toInt(), 4f, floatArrayOf(31f, 60f, 83f, 60f), floatArrayOf(31f, 72f, 69f, 72f), floatArrayOf(31f, 84f, 75f, 84f))
        arrow(104f, 70f, 139f, 70f, 10f, teal, 2.5f)
        polygon(floatArrayOf(171f, 30f, 203f, 48f, 203f, 92f, 171f, 110f, 139f, 92f, 139f, 48f), teal)
        lines(0xFFDCECED.toInt(), 4f, floatArrayOf(158f, 62f, 184f, 62f), floatArrayOf(158f, 74f, 177f, 74f))
        arrow(212f, 70f, 240f, 70f, 9f, teal, 2.5f)
        rect(248f, 40f, 54f, 60f, 8f, 0xFFFFFFFF.toInt(), 0xFF78999C.toInt(), 2f)
        lines(teal, 4f, floatArrayOf(260f, 55f, 290f, 55f), floatArrayOf(260f, 67f, 282f, 67f), floatArrayOf(260f, 79f, 286f, 79f))
    }
}

@Composable
fun SourceDiagram(modifier: Modifier = Modifier) {
    Drawing(640f, 360f, "Offline request path. Browser sends a request. Service worker checks cache and returns a saved response when present.", modifier) {
        rect(26f, 100f, 156f, 106f, 16f, white, 0xFFAAC1C3.toInt(), 2f)
        rect(242f, 78f, 160f, 150f, 18f, teal)
        rect(462f, 100f, 150f, 106f, 16f, white, 0xFFAAC1C3.toInt(), 2f)
        arrow(182f, 151f, 240f, 151f, 9f, teal, 3f)
        arrow(402f, 151f, 462f, 151f, 9f, teal, 3f)
        val route = Path().apply { moveTo(482f, 220f); lineTo(482f, 280f); lineTo(160f, 280f); lineTo(160f, 220f) }
        path(route, stroke = 0xFF6F989C.toInt(), width = 2f, dashed = true)
        lines(0xFF6F989C.toInt(), 2f, floatArrayOf(152f, 230f, 160f, 220f, 168f, 230f))
        lines(0xFF77989C.toInt(), 6f, floatArrayOf(58f, 130f, 148f, 130f), floatArrayOf(58f, 150f, 132f, 150f), floatArrayOf(58f, 170f, 140f, 170f))
        lines(0xFFDBE9EA.toInt(), 6f, floatArrayOf(272f, 124f, 372f, 124f), floatArrayOf(272f, 146f, 348f, 146f), floatArrayOf(272f, 168f, 362f, 168f))
        lines(teal, 5f, floatArrayOf(500f, 132f, 535f, 132f), floatArrayOf(500f, 150f, 573f, 150f), floatArrayOf(500f, 168f, 556f, 168f))
        text("Browser", 104f, 240f, 15f, 0xFF304B52.toInt())
        text("Service worker", 322f, 255f, 15f, 0xFF304B52.toInt())
        text("Cache", 537f, 240f, 15f, 0xFF304B52.toInt())
    }
}

@Composable
fun ConceptArt(index: Int, modifier: Modifier = Modifier) {
    val description = when (index % 4) {
        0 -> "A browser sends a request to a server, which returns a response."
        1 -> "A request reaches a service worker. It can return a saved response or use the network."
        2 -> "A request and response are stored together in cache for later lookup."
        else -> "Lesson text, questions, figures, and media are kept together for offline use."
    }
    Drawing(640f, 360f, description, modifier) {
        when (index % 4) {
            0 -> {
                window(42f, 72f, 208f, 216f, 20f)
                listOf(72f, 96f, 120f).forEach { circle(it, 98f, 7f, accent) }
                lines(ink, 8f, floatArrayOf(74f, 145f, 216f, 145f), floatArrayOf(74f, 177f, 182f, 177f), floatArrayOf(74f, 209f, 198f, 209f), floatArrayOf(74f, 241f, 152f, 241f))
                arrow(270f, 180f, 374f, 180f, 18f, artTeal, 8f)
                window(392f, 98f, 202f, 164f, 20f)
                lines(accent, 8f, floatArrayOf(430f, 144f, 556f, 144f), floatArrayOf(430f, 180f, 522f, 180f), floatArrayOf(430f, 216f, 542f, 216f))
            }
            1 -> {
                window(24f, 130f, 142f, 100f, 18f)
                lines(ink, 8f, floatArrayOf(54f, 163f, 136f, 163f), floatArrayOf(54f, 194f, 112f, 194f))
                arrow(178f, 180f, 258f, 180f, 16f, artTeal, 8f)
                polygon(floatArrayOf(320f, 84f, 406f, 132f, 406f, 228f, 320f, 276f, 234f, 228f, 234f, 132f), windowFill, windowStroke, 3f)
                circle(320f, 180f, 18f, accent)
                arrow(420f, 156f, 472f, 156f, 16f, artTeal, 8f)
                arrow(420f, 204f, 472f, 204f, 16f, artTeal, 8f)
                window(486f, 92f, 126f, 88f, 16f)
                lines(accent, 8f, floatArrayOf(510f, 132f, 588f, 132f))
                window(486f, 188f, 126f, 88f, 16f)
                lines(ink, 8f, floatArrayOf(510f, 224f, 588f, 224f))
            }
            2 -> {
                window(64f, 104f, 152f, 152f, 0f)
                lines(ink, 8f, floatArrayOf(96f, 145f, 184f, 145f), floatArrayOf(96f, 179f, 160f, 179f), floatArrayOf(96f, 213f, 174f, 213f))
                arrow(240f, 180f, 306f, 180f, 16f, artTeal, 8f)
                window(328f, 104f, 152f, 152f, 0f)
                lines(accent, 8f, floatArrayOf(360f, 145f, 448f, 145f), floatArrayOf(360f, 179f, 424f, 179f), floatArrayOf(360f, 213f, 438f, 213f))
                oval(502f, 112f, 594f, 152f, windowFill, windowStroke, 3f)
                val cylinder = Path().apply { moveTo(502f, 132f); lineTo(502f, 222f); cubicTo(502f, 234f, 523f, 244f, 548f, 244f); cubicTo(573f, 244f, 594f, 234f, 594f, 222f); lineTo(594f, 132f) }
                path(cylinder, fill = windowFill, stroke = windowStroke, width = 3f)
                val curve = Path().apply { moveTo(502f, 177f); cubicTo(502f, 189f, 523f, 199f, 548f, 199f); cubicTo(573f, 199f, 594f, 189f, 594f, 177f) }
                path(curve, stroke = windowStroke, width = 3f)
            }
            3 -> {
                window(110f, 60f, 300f, 240f, 22f)
                lines(ink, 8f, floatArrayOf(154f, 112f, 358f, 112f), floatArrayOf(154f, 150f, 278f, 150f))
                listOf(205f, 254f).forEach { y ->
                    circle(176f, y, 18f, windowFill, windowStroke, 3f)
                    lines(accent, 8f, floatArrayOf(167f, y, 174f, y + 7f, 187f, y - 8f))
                }
                lines(ink, 8f, floatArrayOf(216f, 205f, 358f, 205f), floatArrayOf(216f, 254f, 322f, 254f))
                circle(488f, 180f, 66f, windowFill, windowStroke, 3f)
                lines(accent, 8f, floatArrayOf(456f, 180f, 520f, 180f), floatArrayOf(488f, 148f, 488f, 212f))
                lines(artTeal, 8f, floatArrayOf(450f, 262f, 474f, 286f, 524f, 232f))
            }
        }
    }
}

@Composable
fun AnchorMark(modifier: Modifier = Modifier) {
    Drawing(48f, 48f, "", modifier) {
        circle(24f, 5f, 3f, stroke = teal, width = 2.8f)
        lines(teal, 2.8f, floatArrayOf(24f, 8f, 24f, 30f), floatArrayOf(11f, 23f, 6f, 23f), floatArrayOf(37f, 23f, 42f, 23f))
        path(Path().apply { moveTo(16f, 14f); cubicTo(16f, 3f, 32f, 3f, 32f, 14f) }, stroke = teal, width = 2.8f)
        path(Path().apply { moveTo(11f, 23f); lineTo(11f, 28f); cubicTo(11f, 45f, 37f, 45f, 37f, 28f); lineTo(37f, 23f) }, stroke = teal, width = 2.8f)
    }
}

@Composable
private fun Drawing(width: Float, height: Float, description: String, modifier: Modifier, draw: VectorCanvas.() -> Unit) {
    Canvas(modifier.semantics { if (description.isNotEmpty()) contentDescription = description }) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val scale = min(size.width / width, size.height / height)
            native.save()
            native.translate((size.width - width * scale) / 2, (size.height - height * scale) / 2)
            native.scale(scale, scale)
            VectorCanvas(native).draw()
            native.restore()
        }
    }
}

private class VectorCanvas(private val canvas: AndroidCanvas) {
    val teal = Palette.Teal.toArgb()
    val white = Color.White.toArgb()
    val ink = Palette.PlayerInk.toArgb()
    val accent = Palette.Accent.toArgb()
    val artTeal = 0xFF86C9CC.toInt()
    val windowFill = 0x12F5F8F8
    val windowStroke = 0xDBF5F8F8.toInt()
    private fun paint(color: Int, width: Float? = null, dashed: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = if (width == null) Paint.Style.FILL else Paint.Style.STROKE
        strokeWidth = width ?: 0f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        if (dashed) pathEffect = android.graphics.DashPathEffect(floatArrayOf(7f, 7f), 0f)
    }
    fun path(path: Path, fill: Int? = null, stroke: Int? = null, width: Float = 1f, dashed: Boolean = false) {
        if (fill != null) canvas.drawPath(path, paint(fill))
        if (stroke != null) canvas.drawPath(path, paint(stroke, width, dashed))
    }
    fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float, fill: Int, stroke: Int? = null, width: Float = 1f) {
        val rect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, radius, radius, paint(fill))
        if (stroke != null) canvas.drawRoundRect(rect, radius, radius, paint(stroke, width))
    }
    fun window(x: Float, y: Float, w: Float, h: Float, radius: Float) = rect(x, y, w, h, radius, windowFill, windowStroke, 3f)
    fun circle(x: Float, y: Float, radius: Float, fill: Int? = null, stroke: Int? = null, width: Float = 1f) {
        if (fill != null) canvas.drawCircle(x, y, radius, paint(fill))
        if (stroke != null) canvas.drawCircle(x, y, radius, paint(stroke, width))
    }
    fun oval(left: Float, top: Float, right: Float, bottom: Float, fill: Int, stroke: Int, width: Float) {
        canvas.drawOval(RectF(left, top, right, bottom), paint(fill))
        canvas.drawOval(RectF(left, top, right, bottom), paint(stroke, width))
    }
    fun lines(color: Int, width: Float, vararg segments: FloatArray) {
        segments.forEach { points ->
            path(Path().apply { moveTo(points[0], points[1]); for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1]) }, stroke = color, width = width)
        }
    }
    fun polygon(points: FloatArray, fill: Int, stroke: Int? = null, width: Float = 1f) {
        path(Path().apply { moveTo(points[0], points[1]); for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1]); close() }, fill, stroke, width)
    }
    fun arrow(x1: Float, y1: Float, x2: Float, y2: Float, head: Float, color: Int, width: Float) {
        lines(color, width, floatArrayOf(x1, y1, x2, y2), floatArrayOf(x2 - head, y2 - head, x2, y2, x2 - head, y2 + head))
    }
    fun text(text: String, x: Float, y: Float, size: Float, color: Int) {
        canvas.drawText(text, x, y, paint(color).apply { textSize = size; textAlign = Paint.Align.CENTER })
    }
}
