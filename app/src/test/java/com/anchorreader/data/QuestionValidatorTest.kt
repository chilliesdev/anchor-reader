package com.anchorreader.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuestionValidatorTest {
    private val text = "Saved books can be read without a network connection. Reading progress stays on this device."
    private val unit = Concept("Offline", listOf(text), 2, listOf(SourceSpan(2, text, 0, text.length)))
    private val book = Book("book-a", "Source", "", listOf(unit), text)
    private val source = QuestionSource(unit.id, unit.sourceSpans, 3)

    @Test
    fun questionsKeepStableIdentityEvidenceAndVersion() {
        val first = parse()
        val second = parse()
        assertEquals(first, second)
        assertEquals(2, first.sourcePage)
        assertTrue(first.id.isNotEmpty())
        assertTrue(first.version.isNotEmpty())
        val changed = parse(question().put("explanation", "The source says the saved book is available without a connection."))
        assertEquals(first.id, changed.id)
        assertNotEquals(first.version, changed.version)
        assertNotEquals(answerKey(first), answerKey(changed))
    }

    @Test
    fun fabricatedQuoteOrWrongPageCannotBecomeEvidence() {
        rejected { parse(question().put("sourceExcerpt", "All books are free to download.")) }
        rejected { parse(question().put("sourcePage", 3)) }
        rejected { parse(question().put("sourceExcerpt", "Saved")) }
    }

    @Test
    fun invalidAnswerOrDuplicateChoicesAreRejected() {
        rejected { parse(question().put("correctIndex", 4)) }
        rejected { parse(question().put("correctIndex", 1.5)) }
        rejected { parse(question().put("options", JSONArray(listOf("Offline", "offline", "Always", "Never")))) }
        rejected { parse(question().put("options", JSONArray(listOf("A", "B")))) }
    }

    @Test
    fun sectionMismatchExtraFieldsAndRepeatedQuestionsAreRejected() {
        val wrong = payload().put("sectionId", "another-section")
        rejected { QuestionValidator.parse(wrong.toString(), book, 0, source) }
        rejected { parse(question().put("url", "https://example.invalid")) }
        val duplicate = payload().put("questions", JSONArray().put(question()).put(question()))
        rejected { QuestionValidator.parse(duplicate.toString(), book, 0, source) }
        val tooMany = payload().put("questions", JSONArray().put(question()).put(question()).put(question()).put(question()))
        rejected { QuestionValidator.parse(tooMany.toString(), book, 0, source) }
    }

    @Test
    fun insufficientSourceReturnsNoInventedQuestion() {
        val empty = JSONObject().put("sectionId", unit.id).put("questions", JSONArray())
        assertTrue(QuestionValidator.parse(empty.toString(), book, 0, source).isEmpty())
    }

    @Test
    fun repeatedHeadingsAndBodiesOnSamePageHaveDistinctSectionIds() {
        val units = TextSegmenter.segment("# Repeat\n\nSaved books work offline.\n\n# Repeat\n\nSaved books work offline.", 2)
        assertEquals(2, units.size)
        assertEquals(units.first().title, units.last().title)
        assertEquals(units.first().sentences, units.last().sentences)
        assertNotEquals(units.first().id, units.last().id)
        assertEquals(units.map { it.id }, TextSegmenter.segment("# Repeat\n\nSaved books work offline.\n\n# Repeat\n\nSaved books work offline.", 2).map { it.id })
    }

    @Test
    fun chunkingRetainsAllSourceAndOriginalPageIdentity() {
        val longText = (1..5000).joinToString(" ") { "word$it" } + "."
        val longUnit = Concept("Long source", listOf(longText), 7, listOf(SourceSpan(7, longText, 10, 10 + longText.length)))
        val chunks = QuestionSources.chunks(longUnit)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.sectionId == longUnit.id && it.spans.sumOf { span -> span.text.length } <= QuestionSources.MAX_CHUNK_CHARACTERS })
        assertEquals(longText, chunks.flatMap { it.spans }.joinToString("") { it.text })
        assertTrue(chunks.flatMap { it.spans }.all { it.page == 7 })
        assertEquals(10, chunks.first().spans.first().startOffset)
        assertEquals(10 + longText.length, chunks.last().spans.last().endOffset)
    }

    @Test
    fun crossPageSectionsKeepEvidenceOnEachActualPage() {
        val units = TextSegmenter.segmentPages(listOf(
            TextSegmenter.SourcePage(2, "# Offline\n\nSaved books can be read without a network connection."),
            TextSegmenter.SourcePage(3, "Reading progress stays on this device."),
        ))
        assertEquals(1, units.size)
        assertEquals(listOf(2, 3), units.single().sourceSpans.map { it.page })
        val crossBook = book.copy(units = units)
        val q = question().put("sourcePage", 3).put("sourceExcerpt", "Reading progress stays on this device.")
        val data = payload(q).put("sectionId", units.single().id)
        assertEquals(3, QuestionValidator.parse(data.toString(), crossBook, 0, QuestionSource(units.single().id, units.single().sourceSpans, 3)).single().sourcePage)
    }

    private fun parse(q: JSONObject = question()) = QuestionValidator.parse(payload(q).toString(), book, 0, source).single()
    private fun payload(q: JSONObject = question()) = JSONObject().put("sectionId", unit.id).put("questions", JSONArray().put(q))
    private fun question() = JSONObject().put("prompt", "When can a saved book be read?")
        .put("options", JSONArray(listOf("Without a network connection", "Only with a server", "Only after payment", "Never offline")))
        .put("correctIndex", 0).put("explanation", "The source says saved books can be read without a network connection.")
        .put("sourcePage", 2).put("sourceExcerpt", "Saved books can be read without a network connection.")
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Invalid questions were accepted") } catch (_: IllegalArgumentException) { }
    }
}
