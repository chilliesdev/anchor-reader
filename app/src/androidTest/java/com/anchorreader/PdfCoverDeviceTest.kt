package com.anchorreader

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchorreader.ui.PDF_COVER_MAX_SIDE
import com.anchorreader.ui.renderPdfCover
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PdfCoverDeviceTest {
    private lateinit var root: File

    @Before
    fun prepare() {
        root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "cover-test-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun clean() { root.deleteRecursively() }

    @Test
    fun coverIsOriginalFirstPageWithWhiteBackgroundAndBoundedAspect() = runBlocking {
        val file = File(root, "two-pages.pdf")
        val document = PdfDocument()
        try {
            val first = document.startPage(PdfDocument.PageInfo.Builder(900, 1200, 1).create())
            first.canvas.drawRect(0f, 0f, 900f, 600f, Paint().apply { color = Color.RED })
            document.finishPage(first)
            val second = document.startPage(PdfDocument.PageInfo.Builder(1200, 900, 2).create())
            second.canvas.drawColor(Color.BLUE)
            document.finishPage(second)
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        val cover = requireNotNull(renderPdfCover(file))
        try {
            assertTrue(cover.width <= PDF_COVER_MAX_SIDE && cover.height <= PDF_COVER_MAX_SIDE)
            assertEquals(PDF_COVER_MAX_SIDE, cover.height)
            assertEquals(.75, cover.width.toDouble() / cover.height, .002)
            assertEquals(Color.RED, cover.getPixel(cover.width / 2, cover.height / 4))
            assertEquals(Color.WHITE, cover.getPixel(cover.width / 2, cover.height * 3 / 4))
        } finally { cover.recycle() }
    }

    @Test
    fun blankFirstPageStaysBlankInsteadOfUsingSecondPage() = runBlocking {
        val file = File(root, "blank-first.pdf")
        val document = PdfDocument()
        try {
            val first = document.startPage(PdfDocument.PageInfo.Builder(1200, 600, 1).create())
            document.finishPage(first)
            val second = document.startPage(PdfDocument.PageInfo.Builder(1200, 600, 2).create())
            second.canvas.drawColor(Color.BLUE)
            document.finishPage(second)
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        val cover = requireNotNull(renderPdfCover(file))
        try {
            assertEquals(2.0, cover.width.toDouble() / cover.height, .002)
            assertEquals(Color.WHITE, cover.getPixel(cover.width / 2, cover.height / 2))
        } finally { cover.recycle() }
    }

    @Test
    fun missingAndDamagedSourceReturnSafeFallback() = runBlocking {
        assertNull(renderPdfCover(File(root, "missing.pdf")))
        val damaged = File(root, "damaged.pdf").apply { writeText("%PDF-1.4\nbroken") }
        assertNull(renderPdfCover(damaged))
    }
}
