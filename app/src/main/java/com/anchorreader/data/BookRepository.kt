package com.anchorreader.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.anchorreader.data.web.ExtractedArticle
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** All work is synchronous. Call from an IO dispatcher, not the main thread. */
class BookRepository internal constructor(
    private val booksDirectory: File,
    private val progressDirectory: File,
    private val appContext: Context? = null
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "books"),
        File(context.applicationContext.filesDir, "progress"),
        context.applicationContext
    )

    @Volatile
    var storageWarnings: List<String> = emptyList()
        private set

    init {
        check(booksDirectory.exists() || booksDirectory.mkdirs()) { "Could not open book storage." }
        check(progressDirectory.exists() || progressDirectory.mkdirs()) { "Could not open progress storage." }
        if (appContext != null) {
            PDFBoxResourceLoader.init(appContext)
        }
    }

    @Synchronized
    fun loadBooks(): List<Book> {
        val warnings = mutableListOf<String>()
        val books = mutableListOf<Book>()
        metadataFiles().forEach { file ->
            try {
                val book = readBook(readJson(file), file.nameWithoutExtension)
                val pdf = book.pdfPath?.let(::File)
                if (pdf != null && (!pdf.isFile || pdf.length() == 0L)) {
                    warnings += "${book.title}: the source PDF is missing. Import it again to restore the source."
                }
                books += book
            } catch (_: Exception) {
                warnings += "A saved book could not load. Other books are available."
            }
        }
        // Interrupted imports never appear as ready books. Remove only app-owned staging files.
        booksDirectory.listFiles()?.filter { it.name.startsWith(".import-") }?.forEach(File::delete)
        booksDirectory.listFiles()?.filter { it.extension == "pdf" }?.forEach { pdf ->
            val metadata = File(booksDirectory, "${pdf.nameWithoutExtension}.json")
            if (!metadata.exists() && !File(metadata.path + ".bak").exists()) pdf.delete()
        }
        storageWarnings = warnings
        return listOf(SampleLesson.book) + books.sortedByDescending(Book::createdAt)
    }

    @Synchronized
    fun saveText(title: String, author: String, text: String): Book {
        val cleanTitle = validateTitle(title)
        val cleanAuthor = validateAuthor(author)
        val source = text.trim()
        validateSource(source)
        val units = TextSegmenter.segment(source)
        validateUnits(units)
        val book = Book(UUID.randomUUID().toString(), cleanTitle, cleanAuthor, units, source)
        writeBook(book)
        return book
    }

    @Synchronized
    fun saveWebArticle(article: ExtractedArticle): Book {
        val cleanTitle = validateTitle(article.title)
        val cleanAuthor = validateAuthor(article.author)
        val source = article.fullText.trim()
        validateSource(source)
        val units = if (article.units.isNotEmpty()) {
            article.units.mapIndexedNotNull { index, draft ->
                val sentences = TextSegmenter.splitSentences(draft.text)
                if (sentences.isEmpty()) null
                else Concept(
                    title = draft.title.trim().take(120).ifBlank { "Section ${index + 1}" },
                    sentences = sentences
                )
            }.ifEmpty { TextSegmenter.segment(source) }
        } else {
            TextSegmenter.segment(source)
        }
        validateUnits(units)
        val book = Book(UUID.randomUUID().toString(), cleanTitle, cleanAuthor, units, source, sourceUrl = article.sourceUrl)
        writeBook(book)
        return book
    }

    @Synchronized
    fun importPdf(uri: Uri, title: String, author: String): Book {
        val cleanTitle = validateTitle(title)
        val cleanAuthor = validateAuthor(author)
        val id = UUID.randomUUID().toString()
        val staging = File(booksDirectory, ".import-$id.pdf")
        val original = File(booksDirectory, "$id.pdf")
        var published = false
        try {
            val input = appContext?.contentResolver?.openInputStream(uri)
                ?: throw IOException("Could not open this PDF. Choose the file again.")
            input.use { source ->
                staging.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var bytes = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count == -1) break
                        bytes += count
                        require(bytes <= MAX_PDF_BYTES) { "PDF files must be 25 MB or smaller." }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            validatePdfSignature(staging)
            val text = PDDocument.load(staging).use(::readPdfText)
            val source = text.pages.joinToString("\n\n") { it.text }
            val units = TextSegmenter.segmentPages(text.pages, text.sections)
            validateSource(source)
            validateUnits(units)
            check(staging.renameTo(original)) { "Could not save the source PDF. Check available storage." }
            val book = Book(id, cleanTitle, cleanAuthor, units, source, original.absolutePath)
            writeBook(book)
            published = true
            return book
        } catch (error: InvalidPasswordException) {
            throw IllegalArgumentException("Encrypted PDFs are not supported. Choose an unprotected PDF.", error)
        } catch (error: IOException) {
            throw IOException("Could not read or save this PDF. The file may be damaged, or storage may be full.", error)
        } finally {
            staging.delete()
            if (!published) {
                original.delete()
                deleteAtomic(File(booksDirectory, "$id.json"))
            }
        }
    }

    @Synchronized
    fun saveProgress(bookId: String, progress: Progress) {
        val book = requireBook(bookId)
        writeProgress(book, progress)
    }

    private fun writeProgress(book: Book, progress: Progress) {
        val clean = ProgressRules.normalize(book, progress)
        val json = JSONObject().put("version", SCHEMA_VERSION)
            .put("bookId", book.id)
            .put("unitIndex", clean.unitIndex)
            .put("sentenceIndex", clean.sentenceIndex)
            .put("visited", JSONArray(clean.visited.sorted()))
            .put("completed", clean.completed)
        val answers = JSONObject()
        clean.answers.forEach { (index, outcome) -> answers.put(index.toString(), outcome.name) }
        json.put("answers", answers)
        writeJson(File(progressDirectory, "${book.id}.json"), json)
    }

    @Synchronized
    fun loadProgress(bookId: String): Progress {
        val book = requireBook(bookId)
        val file = File(progressDirectory, "${book.id}.json")
        if (!file.exists() && !File(file.path + ".bak").exists()) return Progress()
        return try {
            val json = readJson(file)
            readProgress(json, book)
        } catch (_: Exception) {
            storageWarnings = storageWarnings + "${book.title}: saved progress could not load. The lesson is available."
            Progress()
        }
    }

    private fun readProgress(json: JSONObject, book: Book): Progress {
        require(json.getString("bookId") == book.id) { "Invalid progress record." }
        val visitedJson = json.optJSONArray("visited") ?: JSONArray()
        val visited = (0 until visitedJson.length()).map { visitedJson.getInt(it) }.toSet()
        val answersJson = json.optJSONObject("answers") ?: JSONObject()
        val answers = mutableMapOf<Int, Outcome>()
        answersJson.keys().forEach { key ->
            val index = key.toIntOrNull() ?: return@forEach
            val outcome = runCatching { Outcome.valueOf(answersJson.getString(key)) }.getOrNull()
            if (outcome != null) answers[index] = outcome
        }
        return ProgressRules.normalize(book, Progress(
            json.optInt("unitIndex", 0), json.optInt("sentenceIndex", 0), visited, answers,
            json.optBoolean("completed", false),
        ))
    }

    @Synchronized
    fun delete(book: Book) {
        require(!book.isSample && book.id != SampleLesson.book.id) { "The prepared sample stays in the app." }
        val savedBook = requireBook(book.id)
        savedBook.pdfPath?.let { path ->
            val pdf = File(path)
            check(!pdf.exists() || pdf.delete()) { "Could not delete this PDF. Try again." }
        }
        val metadata = File(booksDirectory, "${savedBook.id}.json")
        deleteAtomic(metadata)
        check(!metadata.exists()) { "Could not delete this book. Try again." }
        resetProgress(savedBook.id)
    }

    @Synchronized
    fun resetProgress(bookId: String) {
        validateId(bookId)
        deleteAtomic(File(progressDirectory, "$bookId.json"))
    }

    @Synchronized
    fun resetAllProgress() {
        progressDirectory.listFiles()?.forEach { file ->
            check(file.delete()) { "Could not reset all progress. Try again." }
        }
    }

    @Synchronized
    fun storedBytes(): Long = listOf(booksDirectory, progressDirectory)
        .sumOf { directory -> directory.walkTopDown().filter(File::isFile).sumOf(File::length) }

    private fun requireBook(id: String): Book {
        validateId(id)
        if (id == SampleLesson.book.id) return SampleLesson.book
        return readBook(readJson(File(booksDirectory, "$id.json")), id)
    }

    @Synchronized
    fun findBook(id: String): Book? = runCatching { requireBook(id) }.getOrNull()

    private fun validateId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid book identifier." }
    }

    private fun validateTitle(title: String): String = title.trim().also {
        require(it.isNotEmpty()) { "Add a title to continue." }
        require(it.length <= 120) { "Titles must have 120 characters or fewer." }
    }

    private fun validateAuthor(author: String): String = author.trim().also {
        require(it.length <= 120) { "Author names must have 120 characters or fewer." }
    }

    private fun validateSource(source: String) {
        require(source.count(Char::isLetterOrDigit) >= 3) { "This document has no usable text. Scanned PDFs are not supported." }
        require(source.length <= MAX_SOURCE_CHARACTERS) { "This document has too much text. Import a shorter document." }
    }

    private fun validateUnits(units: List<Concept>) {
        require(units.isNotEmpty()) { "This document has no readable text." }
        require(units.size <= MAX_CONCEPTS) { "This document has too many sections. Import a shorter document." }
    }

    private fun validatePdfSignature(file: File) {
        val header = ByteArray(1024)
        val length = file.inputStream().use { it.read(header) }
        require(length > 0 && String(header, 0, length, Charsets.ISO_8859_1).contains("%PDF-")) {
            "Choose a valid PDF file to continue."
        }
    }

    private data class PdfText(val pages: List<TextSegmenter.SourcePage>, val sections: List<TextSegmenter.SourceSection>)

    private fun readPdfText(document: PDDocument): PdfText {
        require(!document.isEncrypted) { "Encrypted PDFs are not supported. Choose an unprotected PDF." }
        require(document.numberOfPages in 1..MAX_PDF_PAGES) { "PDF files must have 1 to 200 pages." }
        val stripper = PdfHeadingExtractor()
        var sourceCharacters = 0
        val pages = mutableListOf<TextSegmenter.SourcePage>()
        for (pageIndex in 0 until document.numberOfPages) {
            val text = stripper.readPage(document, pageIndex + 1)
            sourceCharacters += text.length
            require(sourceCharacters <= MAX_SOURCE_CHARACTERS) { "This PDF has too much text. Import a shorter document." }
            val usableText = text.count(Char::isLetterOrDigit) >= 3
            // The first page is also the cover. A digital body may follow an image-only cover.
            require(usableText || pageIndex == 0 || !document.getPage(pageIndex).hasContents()) {
                "Page ${pageIndex + 1} has no usable text. Scanned or image-only pages are not supported."
            }
            if (usableText) pages += TextSegmenter.SourcePage(pageIndex + 1, text)
        }
        return PdfText(pages, pdfSections(document) + stripper.headings())
    }

    private fun pdfSections(document: PDDocument): List<TextSegmenter.SourceSection> {
        val root = document.documentCatalog.documentOutline ?: return emptyList()
        val pending = ArrayDeque<PDOutlineItem>()
        root.firstChild?.let(pending::addLast)
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<com.tom_roush.pdfbox.cos.COSDictionary, Boolean>(),
        )
        val result = mutableListOf<TextSegmenter.SourceSection>()
        while (pending.isNotEmpty()) {
            val item = pending.removeFirst()
            if (!seen.add(item.cosObject)) continue
            require(seen.size <= MAX_CONCEPTS) { "This document has too many sections. Import a shorter document." }
            item.nextSibling?.let(pending::addFirst)
            item.firstChild?.let(pending::addFirst)
            val page = runCatching { item.findDestinationPage(document) }.getOrNull() ?: continue
            val index = document.pages.indexOf(page)
            val title = item.title?.trim().orEmpty()
            if (index >= 0 && title.isNotEmpty()) result += TextSegmenter.SourceSection(title, index + 1)
        }
        return result.distinct()
    }

    private fun metadataFiles(): List<File> = booksDirectory.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
        .map { if (it.name.endsWith(".bak")) File(it.path.removeSuffix(".bak")) else it }
        .distinctBy(File::getAbsolutePath)

    private fun writeBook(book: Book) {
        val units = JSONArray()
        book.units.forEach { unit ->
            units.put(JSONObject().put("title", unit.title).put("sentences", JSONArray(unit.sentences))
                .put("sourcePage", unit.sourcePage ?: JSONObject.NULL)
                .put("sourceStartOffset", unit.sourceStartOffset)
                .put("sourceSpans", JSONArray(unit.sourceSpans.map { span -> JSONObject()
                    .put("page", span.page).put("text", span.text)
                    .put("start", span.startOffset).put("end", span.endOffset) })))
        }
        writeJson(File(booksDirectory, "${book.id}.json"), JSONObject()
            .put("version", SCHEMA_VERSION).put("id", book.id).put("title", book.title)
            .put("author", book.author).put("units", units).put("sourceText", book.sourceText)
            .put("pdfFile", book.pdfPath?.let { File(it).name } ?: JSONObject.NULL)
            .put("sourceUrl", book.sourceUrl ?: JSONObject.NULL)
            .put("createdAt", book.createdAt))
    }

    private fun readBook(json: JSONObject, expectedId: String): Book {
        val id = json.getString("id")
        validateId(id)
        require(id == expectedId) { "Invalid book record." }
        val unitsJson = json.getJSONArray("units")
        val units = (0 until unitsJson.length()).map { index ->
            val unit = unitsJson.getJSONObject(index)
            val sentencesJson = unit.getJSONArray("sentences")
            val sentences = (0 until sentencesJson.length()).map { sentencesJson.getString(it) }
            require(sentences.isNotEmpty() && sentences.all {
                it.isNotBlank() && it.length <= MAX_SOURCE_CHARACTERS
            }) { "Invalid source text." }
            val page = if (unit.isNull("sourcePage")) null else unit.getInt("sourcePage")
            require(page == null || page in 1..MAX_PDF_PAGES) { "Invalid source page." }
            val spansJson = unit.optJSONArray("sourceSpans") ?: JSONArray()
            val spans = runCatching { (0 until spansJson.length()).map { n ->
                val span = spansJson.getJSONObject(n)
                val text = span.getString("text")
                val start = span.getInt("start")
                val end = span.getInt("end")
                val number = span.getInt("page")
                require(number in 1..MAX_PDF_PAGES && start >= 0 && end == start + text.length && text.length <= MAX_SOURCE_CHARACTERS) { "Invalid source span." }
                SourceSpan(number, text, start, end)
            } }.getOrDefault(emptyList())
            Concept(unit.getString("title"), sentences, page, spans, unit.optInt("sourceStartOffset", 0).coerceAtLeast(0))
        }
        validateUnits(units)
        val source = json.getString("sourceText")
        validateSource(source)
        val pdfFile = if (json.isNull("pdfFile")) null else json.getString("pdfFile")
        require(pdfFile == null || pdfFile == "$id.pdf") { "Invalid PDF path." }
        val sourceUrl = if (json.isNull("sourceUrl")) null else json.optString("sourceUrl").takeIf { it.isNotBlank() }
        val book = Book(id, validateTitle(json.getString("title")), validateAuthor(json.optString("author")),
            units, source, pdfFile?.let { File(booksDirectory, it).absolutePath }, sourceUrl, false, json.getLong("createdAt"))
        return if (json.getInt("version") < SCHEMA_VERSION) migrateBook(book) else book
    }

    private fun migrateBook(previous: Book): Book {
        val pdfText = previous.pdfPath?.let { path ->
            runCatching { PDDocument.load(File(path)).use(::readPdfText) }.getOrNull()
        }
        val segmented = if (pdfText != null) TextSegmenter.segmentPages(pdfText.pages, pdfText.sections)
            else TextSegmenter.segment(previous.sourceText)
        // A legacy title-only source must stay readable even when it has no section body.
        val units = segmented.ifEmpty { previous.units }
        validateUnits(units)
        var current = previous.copy(units = units)
        if (pdfText == null && previous.pdfPath != null) current = BookProgressMigration.restoreSourcePages(previous, current)
        val progressFile = File(progressDirectory, "${previous.id}.json")
        if (progressFile.exists() || File(progressFile.path + ".bak").exists()) {
            val json = runCatching { readJson(progressFile) }.getOrNull()
            if (json != null && json.getInt("version") == 1) {
                val progress = runCatching { readProgress(json, previous) }.getOrNull()
                if (progress != null) writeProgress(current, BookProgressMigration.remap(previous, current, progress))
            }
        }
        // Progress first: a stopped migration can rebuild the same sections and retain its mapped position.
        writeBook(current)
        return current
    }

    private fun readJson(file: File): JSONObject {
        val bytes = try {
            AtomicFile(file).openRead().use { it.readBytes() }
        } catch (_: RuntimeException) {
            val backup = File(file.path + ".bak")
            val target = if (backup.exists()) backup else file
            target.readBytes()
        }
        require(bytes.size.toLong() <= MAX_RECORD_BYTES) { "Stored record is too large." }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.getInt("version") in 1..SCHEMA_VERSION) { "Unsupported stored record version." }
        return json
    }

    private fun writeJson(file: File, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        try {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        } catch (e: RuntimeException) {
            if (e.message?.contains("not mocked") == true) {
                val backup = File(file.path + ".bak")
                if (file.exists()) {
                    backup.delete()
                    file.renameTo(backup)
                }
                try {
                    file.outputStream().use { out ->
                        out.write(bytes)
                        out.flush()
                        out.fd.sync()
                    }
                    backup.delete()
                } catch (writeError: Exception) {
                    file.delete()
                    if (backup.exists()) backup.renameTo(file)
                    throw writeError
                }
            } else {
                throw e
            }
        }
    }

    private fun deleteAtomic(file: File) {
        try {
            AtomicFile(file).delete()
        } catch (_: RuntimeException) {
            File(file.path + ".bak").delete()
            file.delete()
        }
    }

    companion object {
        const val MAX_PDF_BYTES = 25L * 1024 * 1024
        const val MAX_PDF_PAGES = 200
        const val MAX_SOURCE_CHARACTERS = 1_000_000
        private const val MAX_CONCEPTS = 3_000
        private const val MAX_RECORD_BYTES = 16L * 1024 * 1024
        private const val SCHEMA_VERSION = 3
    }
}
