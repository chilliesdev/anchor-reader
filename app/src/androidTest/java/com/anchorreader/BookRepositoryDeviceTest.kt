package com.anchorreader

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchorreader.data.BookRepository
import com.anchorreader.data.Progress
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/** Runs Android's real content resolver and PDF parser in an isolated app folder. */
@RunWith(AndroidJUnit4::class)
class BookRepositoryDeviceTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: BookRepository

    @Before
    fun prepare() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.cacheDir, "repository-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        repository = BookRepository(context)
    }

    @After
    fun clean() { root.deleteRecursively() }

    @Test
    fun textBookAndProgressSurviveReloadAndDelete() {
        val book = repository.saveText("QA source", "QA author", "One useful idea. A second complete sentence.")
        val record = Progress(visited = setOf(0), completed = true)
        repository.saveProgress(book.id, record)
        val reopened = BookRepository(context)
        assertEquals(book, reopened.loadBooks().first { it.id == book.id })
        assertEquals(record, reopened.loadProgress(book.id))
        assertTrue(reopened.storedBytes() > 0)
        reopened.delete(book)
        assertFalse(BookRepository(context).loadBooks().any { it.id == book.id })
        assertFalse(File(context.filesDir, "progress/${book.id}.json").exists())
    }

    @Test
    fun digitalPdfStoresReadableUnitsAndPrivateOriginal() {
        val input = fixture("digital-source.pdf")
        val book = repository.importPdf(Uri.fromFile(input), "Offline Reading", "QA author")
        assertTrue(book.sourceText.contains("A saved lesson can open without a network."))
        assertTrue(book.units.isNotEmpty())
        assertTrue(book.units.all { it.sourcePage == 1 })
        val original = File(requireNotNull(book.pdfPath))
        assertTrue(original.isFile)
        assertFalse(original.canonicalPath == input.canonicalPath)
        assertArrayEquals(input.readBytes(), original.readBytes())
        input.delete()
        assertEquals(book, BookRepository(context).loadBooks().first { it.id == book.id })
        repository.saveProgress(book.id, Progress(visited = setOf(0)))
        repository.delete(book)
        assertFalse(original.exists())
        assertFalse(BookRepository(context).loadBooks().any { it.id == book.id })
        assertFalse(File(context.filesDir, "progress/${book.id}.json").exists())
    }

    @Test
    fun imageCoverAndTopicsAcrossPdfPagesPreserveSentencesAndSource() {
        val input = File(root, "covered-topics.pdf")
        PDDocument().use { document ->
            imagePage(document)
            textPage(document, listOf("Chapter 1: Cache", "A cache saves a response.", "This sentence starts on one page"))
            textPage(document, listOf("and ends on the next page.", "Another sentence stays in this topic.", "Chapter 2: Network", "A request uses the network.", "The server sends a response."))
            document.save(input)
        }
        val book = repository.importPdf(Uri.fromFile(input), "Topics", "QA author")
        assertEquals(listOf("Chapter 1: Cache", "Chapter 2: Network"), book.units.map { it.title })
        assertEquals(listOf(2, 3), book.units.map { it.sourcePage })
        assertEquals(listOf(3, 2), book.units.map { it.sentences.size })
        assertEquals("This sentence starts on one page and ends on the next page.", book.units.first().sentences[1])
        assertEquals("QA author", book.author)
        assertArrayEquals(input.readBytes(), File(requireNotNull(book.pdfPath)).readBytes())
        repository.saveProgress(book.id, Progress(0, 2, setOf(0)))
        val reopened = BookRepository(context)
        assertEquals(book, reopened.loadBooks().first { it.id == book.id })
        assertEquals(2, reopened.loadProgress(book.id).sentenceIndex)
    }

    @Test
    fun pdfBookmarksNameSectionsWithoutPrintedHeadings() {
        val input = File(root, "outlined-topics.pdf")
        PDDocument().use { document ->
            val first = textPage(document, listOf("A cache saves responses.", "It can reopen a file."))
            val second = textPage(document, listOf("A network fetches missing files.", "The response is saved."))
            val outline = PDDocumentOutline()
            outline.addLast(PDOutlineItem().apply { title = "why a cache helps"; setDestination(first) })
            outline.addLast(PDOutlineItem().apply { title = "when the network is needed"; setDestination(second) })
            document.documentCatalog.documentOutline = outline
            document.save(input)
        }
        val book = repository.importPdf(Uri.fromFile(input), "Bookmarked", "")
        assertEquals(listOf("why a cache helps", "when the network is needed"), book.units.map { it.title })
        assertEquals(listOf(1, 2), book.units.map { it.sourcePage })
        assertEquals(listOf(2, 2), book.units.map { it.sentences.size })
    }

    @Test
    fun imageOnlyBodyStillFailsWithoutCreatingAnImport() {
        val input = File(root, "scanned-body.pdf")
        PDDocument().use { document ->
            textPage(document, listOf("A readable first page."))
            imagePage(document)
            document.save(input)
        }
        rejected { repository.importPdf(Uri.fromFile(input), "Scanned body", "") }
        assertEquals(1, repository.loadBooks().size)
        assertTrue(File(context.filesDir, "books").listFiles().isNullOrEmpty())
    }

    @Test
    fun longCompleteSentencePersistsWithoutBecomingExtraProgressSteps() {
        val sentence = (1..600).joinToString(" ") { "word$it" } + "."
        val book = repository.saveText("Long sentence", "Author", "# Topic\n\n$sentence Last sentence.")
        assertEquals(listOf(sentence, "Last sentence."), book.units.single().sentences)
        repository.saveProgress(book.id, Progress(0, 1, setOf(0)))
        val reopened = BookRepository(context)
        assertEquals(book, reopened.loadBooks().first { it.id == book.id })
        assertEquals(1, reopened.loadProgress(book.id).sentenceIndex)
    }

    @Test
    fun oldTextRecordsMigrateTopicsAndResumeAtTheSameSourceSentence() {
        val id = "old-text-book"
        val source = "# First topic\n\nFirst sentence.\n\nSecond sentence. Third sentence.\n\n# Second topic\n\nFourth sentence."
        oldBook(id, source, listOf(
            listOf("# First topic First sentence."),
            listOf("Second sentence.", "Third sentence."),
            listOf("# Second topic Fourth sentence."),
        ))
        oldProgress(id, 1, 1, setOf(0, 1))
        val migrated = repository.loadBooks().first { it.id == id }
        assertEquals(listOf("First topic", "Second topic"), migrated.units.map { it.title })
        assertEquals(source, migrated.sourceText)
        assertEquals("Old author", migrated.author)
        assertEquals(123L, migrated.createdAt)
        assertEquals(Progress(0, 2, setOf(0)), repository.loadProgress(id))
        assertEquals(3, JSONObject(File(context.filesDir, "books/$id.json").readText()).getInt("version"))
        assertEquals(3, JSONObject(File(context.filesDir, "progress/$id.json").readText()).getInt("version"))
        assertEquals(migrated, BookRepository(context).loadBooks().first { it.id == id })
        assertEquals(Progress(0, 2, setOf(0)), BookRepository(context).loadProgress(id))
    }

    @Test
    fun stoppedMigrationKeepsAlreadyMappedProgressWhenItRetries() {
        val id = "stopped-migration"
        val source = "# Topic\n\nFirst sentence. Second sentence. Third sentence."
        oldBook(id, source, listOf(listOf("# Topic First sentence."), listOf("Second sentence.", "Third sentence.")))
        oldProgress(id, 0, 2, setOf(0), version = 2)
        val book = repository.loadBooks().first { it.id == id }
        assertEquals(3, book.units.single().sentences.size)
        assertEquals(2, repository.loadProgress(id).sentenceIndex)
    }

    @Test
    fun oldPdfImportsMigrateAcrossPagesAndKeepOriginalAndResumePosition() {
        val id = "old-pdf-book"
        val original = File(context.filesDir, "books/$id.pdf")
        PDDocument().use { document ->
            textPage(document, listOf("Chapter 1: Cache", "First sentence."))
            textPage(document, listOf("Second sentence.", "Chapter 2: Network", "Third sentence.", "Fourth sentence."))
            document.save(original)
        }
        val originalBytes = original.readBytes()
        val source = "Chapter 1: Cache\nFirst sentence.\n\nSecond sentence.\nChapter 2: Network\nThird sentence.\nFourth sentence."
        oldBook(id, source, listOf(
            listOf("Chapter 1: Cache First sentence."),
            listOf("Second sentence."),
            listOf("Chapter 2: Network Third sentence.", "Fourth sentence."),
        ), listOf(1, 2, 2), "$id.pdf")
        oldProgress(id, 2, 1, setOf(0, 1, 2))
        val migrated = repository.loadBooks().first { it.id == id }
        assertEquals(listOf("Chapter 1: Cache", "Chapter 2: Network"), migrated.units.map { it.title })
        assertEquals(listOf(1, 2), migrated.units.map { it.sourcePage })
        assertEquals(listOf(2, 2), migrated.units.map { it.sentences.size })
        assertEquals(source, migrated.sourceText)
        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals(Progress(1, 1, setOf(0, 1)), repository.loadProgress(id))
        assertEquals(migrated, BookRepository(context).loadBooks().first { it.id == id })
        assertEquals(Progress(1, 1, setOf(0, 1)), BookRepository(context).loadProgress(id))
    }

    @Test
    fun missingOriginalPdfStillMigratesRetainedTextAndPageLocations() {
        val id = "missing-old-pdf"
        val source = "# First\n\nFirst sentence.\n\n# Second\n\nSecond sentence."
        oldBook(id, source, listOf(listOf("# First First sentence."), listOf("# Second Second sentence.")),
            listOf(2, 4), "$id.pdf")
        oldProgress(id, 1, 0, setOf(1))
        val migrated = repository.loadBooks().first { it.id == id }
        assertEquals(listOf("First", "Second"), migrated.units.map { it.title })
        assertEquals(listOf(2, 4), migrated.units.map { it.sourcePage })
        assertEquals(source, migrated.sourceText)
        assertEquals(Progress(1, 0, setOf(1)), repository.loadProgress(id))
        assertTrue(repository.storageWarnings.any { it.contains("source PDF is missing") })
    }

    @Test
    fun invalidPdfInputsDoNotCreateBooks() {
        val before = repository.loadBooks().map { it.id }
        rejected { repository.importPdf(Uri.fromFile(fixture("damaged-source.pdf")), "Damaged", "") }
        rejected { repository.importPdf(Uri.fromFile(fixture("truncated-source.pdf")), "Truncated", "") }
        rejected { repository.importPdf(Uri.fromFile(fixture("empty-source.pdf")), "Empty", "") }
        val large = File(root, "large.pdf")
        RandomAccessFile(large, "rw").use { file ->
            file.write("%PDF-1.4\n".toByteArray())
            file.setLength(25L * 1024 * 1024 + 1)
        }
        rejected { repository.importPdf(Uri.fromFile(large), "Large", "") }
        assertEquals(before, repository.loadBooks().map { it.id })
        assertTrue("Failed imports left private files.", File(context.filesDir, "books").listFiles().isNullOrEmpty())
    }

    @Test
    fun protectedAndOverLimitPdfsAreRejected() {
        val protected = File(root, "protected.pdf")
        PDDocument.load(fixture("digital-source.pdf")).use { document ->
            document.protect(StandardProtectionPolicy("owner-secret", "reader-secret", AccessPermission()))
            document.save(protected)
        }
        rejected { repository.importPdf(Uri.fromFile(protected), "Protected", "") }
        val longPdf = File(root, "long.pdf")
        PDDocument().use { document ->
            repeat(201) { document.addPage(PDPage()) }
            document.save(longPdf)
        }
        rejected { repository.importPdf(Uri.fromFile(longPdf), "Too many pages", "") }
        assertFalse(repository.loadBooks().any { it.title == "Protected" || it.title == "Too many pages" })
    }

    @Test
    fun requiredTextFieldsDoNotCreateEmptyBooks() {
        val before = repository.loadBooks().map { it.id }
        rejected { repository.saveText("", "", "Useful text.") }
        rejected { repository.saveText("Title", "", "   ") }
        assertEquals(before, repository.loadBooks().map { it.id })
    }

    @Test
    fun pdfLineAndPageContinuationsKeepNamesAcronymsAndNumberedSourceWords() {
        val file = File(root, "continuations.pdf")
        PDDocument().use { document ->
            textPage(document, listOf("Chapter 1: Travel", "I visited"))
            textPage(document, listOf("New York", "last year.", "NASA", "tracks the launch.", "1. Review the source", "before you act."))
            document.save(file)
        }
        val imported = repository.importPdf(Uri.fromFile(file), "Source continuations", "QA")
        val unit = imported.units.single()
        assertEquals("Chapter 1: Travel", unit.title)
        assertEquals(1, unit.sourcePage)
        assertEquals("I visited New York last year. NASA tracks the launch. 1. Review the source before you act.", unit.sentences.joinToString(" "))
        assertEquals(imported.units, BookRepository(context).loadBooks().first { it.id == imported.id }.units)
    }

    @Test
    fun ordinaryPdfFontHeadingsGiveTopicsAcrossPagesWithoutMarkupOrBookmarks() {
        val file = File(root, "styled-topics.pdf")
        PDDocument().use { document ->
            imagePage(document)
            styledPage(document, listOf("One river" to true, "The source sentence starts" to false))
            styledPage(document, listOf("on the first page and ends here." to false, "Second river" to true, "The second topic has a complete sentence." to false))
            document.save(file)
        }
        val book = repository.importPdf(Uri.fromFile(file), "Styled topics", "QA")
        assertEquals(listOf("One river", "Second river"), book.units.map { it.title })
        assertEquals(listOf(2, 3), book.units.map { it.sourcePage })
        assertEquals(listOf("The source sentence starts on the first page and ends here.", "The second topic has a complete sentence."), book.units.map { it.sentences.single() })
    }

    @Test
    fun conventionalAuditPdfsKeepExactTopicsAndThreeAndFiveSentenceCounts() {
        val expected = listOf(
            listOf("Read the river guide before a trip.", "Check the water depth before you launch.", "A trained guide can help you plan a route and watch the river as the weather changes."),
            listOf("Keep the boat clean after each trip.", "Store all safety tools in a dry box.", "Check the rope before use.", "Repair damage before another trip.", "Return the boat to its safe place."),
        )
        listOf("river-guide.pdf", "river-guide-numbered.pdf").forEachIndexed { index, name ->
            val book = repository.importPdf(Uri.fromFile(fixture(name)), "River guide $index", "QA")
            val titles = if (index == 0) listOf("River Safety", "Boat Care") else listOf("1. River Safety", "2. Boat Care")
            assertEquals(titles, book.units.map { it.title })
            assertEquals(listOf(2, 3), book.units.map { it.sourcePage })
            assertEquals(expected, book.units.map { it.sentences })
            assertEquals(listOf(3, 5), book.units.map { it.sentences.size })
        }
    }

    @Test
    fun shortPdfSectionsUseBodyEvidenceWhenHeadingsHaveMostGlyphsAndFooterUsesSmallFont() {
        val file = File(root, "short-topics.pdf")
        PDDocument().use { document ->
            styledPage(document, listOf("Executive Summary" to true, "It works." to false))
            styledPage(document, listOf("Next Topic" to true, "It ends." to false))
            PDPageContentStream(document, document.getPage(0), PDPageContentStream.AppendMode.APPEND, true).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 8f)
                stream.newLineAtOffset(48f, 20f)
                stream.showText("This small footer has more glyphs than both complete body sentences.")
                stream.endText()
            }
            document.save(file)
        }
        val book = repository.importPdf(Uri.fromFile(file), "Short topics", "QA")
        assertEquals(listOf("Executive Summary", "Next Topic"), book.units.map { it.title })
        assertEquals("It works.", book.units.first().sentences.first())
        assertEquals(listOf("It ends."), book.units.last().sentences)
        assertTrue(book.units.first().sentences.any { it.contains("small footer") })
    }

    @Test
    fun corruptRecordDoesNotHideOtherBooks() {
        val book = repository.saveText("Valid source", "", "This readable book must remain available.")
        File(context.filesDir, "books/broken.json").writeText("{broken")
        val reopened = BookRepository(context)
        assertTrue(reopened.loadBooks().any { it.id == book.id })
        assertTrue(reopened.storageWarnings.isNotEmpty())
    }

    @Test
    fun mismatchedLegacyIdentityCannotOverwriteValidMetadataOrProgress() {
        val kept = repository.saveText("Kept book", "Kept author", "First saved sentence. Second saved sentence.")
        repository.saveProgress(kept.id, Progress(sentenceIndex = 1, visited = setOf(0)))
        val metadata = File(context.filesDir, "books/${kept.id}.json")
        val progress = File(context.filesDir, "progress/${kept.id}.json")
        progress.writeText(JSONObject(progress.readText()).put("version", 1).toString())
        val beforeMetadata = metadata.readBytes()
        val beforeProgress = progress.readBytes()
        val wrong = File(context.filesDir, "books/wrong.json")
        wrong.writeText(JSONObject(metadata.readText()).put("version", 1).put("title", "Wrong legacy title").toString())
        val reopened = BookRepository(context)
        assertEquals(kept, reopened.loadBooks().first { it.id == kept.id })
        assertTrue(reopened.storageWarnings.isNotEmpty())
        assertArrayEquals(beforeMetadata, metadata.readBytes())
        assertArrayEquals(beforeProgress, progress.readBytes())
        rejected { reopened.saveProgress("wrong", Progress()) }
        assertArrayEquals(beforeMetadata, metadata.readBytes())
        assertArrayEquals(beforeProgress, progress.readBytes())
        assertEquals(1, reopened.loadProgress(kept.id).sentenceIndex)
    }

    private fun fixture(name: String): File = File(root, name).also { output ->
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            output.outputStream().use { input.copyTo(it) }
        }
    }

    private fun textPage(document: PDDocument, lines: List<String>): PDPage {
        val page = PDPage()
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 12f)
            stream.setLeading(22f)
            stream.newLineAtOffset(48f, 740f)
            lines.forEachIndexed { index, line ->
                if (index > 0) stream.newLine()
                stream.showText(line)
            }
            stream.endText()
        }
        return page
    }

    private fun styledPage(document: PDDocument, lines: List<Pair<String, Boolean>>) {
        val page = PDPage()
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            stream.beginText()
            stream.setLeading(38f)
            stream.newLineAtOffset(48f, 740f)
            lines.forEachIndexed { index, (line, heading) ->
                if (index > 0) stream.newLine()
                stream.setFont(if (heading) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA, if (heading) 24f else 12f)
                stream.showText(line)
            }
            stream.endText()
        }
    }

    private fun imagePage(document: PDDocument) {
        val page = PDPage()
        document.addPage(page)
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            PDPageContentStream(document, page).use { stream ->
                stream.drawImage(LosslessFactory.createFromImage(document, image), 48f, 400f, 300f, 300f)
            }
        } finally {
            image.recycle()
        }
    }

    private fun oldBook(id: String, source: String, units: List<List<String>>,
                        sourcePages: List<Int?> = units.map { null }, pdfFile: String? = null) {
        val jsonUnits = JSONArray()
        units.forEachIndexed { index, sentences ->
            jsonUnits.put(JSONObject().put("title", "Old unit ${index + 1}")
                .put("sentences", JSONArray(sentences)).put("sourcePage", sourcePages[index] ?: JSONObject.NULL))
        }
        File(context.filesDir, "books/$id.json").writeText(JSONObject()
            .put("version", 1).put("id", id).put("title", "Old title").put("author", "Old author")
            .put("units", jsonUnits).put("sourceText", source).put("pdfFile", pdfFile ?: JSONObject.NULL)
            .put("createdAt", 123L).toString())
    }

    private fun oldProgress(id: String, unit: Int, sentence: Int, visited: Set<Int>, version: Int = 1) {
        File(context.filesDir, "progress/$id.json").writeText(JSONObject()
            .put("version", version).put("bookId", id).put("unitIndex", unit).put("sentenceIndex", sentence)
            .put("visited", JSONArray(visited.toList())).put("answers", JSONObject()).put("completed", false).toString())
    }

    private fun rejected(action: () -> Unit) {
        try {
            action()
            fail("Invalid source was accepted.")
        } catch (expected: Exception) {
            assertTrue(expected is IllegalArgumentException || expected is IOException)
            assertFalse(expected.message.isNullOrBlank())
        }
    }
}
