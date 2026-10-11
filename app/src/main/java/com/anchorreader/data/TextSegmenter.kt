package com.anchorreader.data

import java.text.BreakIterator
import java.util.Locale

/** Uses source sections, without summarizing text or inventing topic boundaries. */
object TextSegmenter {
    data class SourcePage(val number: Int, val text: String)
    data class SourceSection(val title: String, val sourcePage: Int)

    private data class Line(val text: String, val page: Int?)
    private data class Boundary(val title: String, val heading: Boolean)

    private const val DOCUMENT_TITLE = "Full document"
    private val whitespace = Regex("\\s+")
    private val abbreviation = Regex("(?i)(?:\\b(?:Mr|Mrs|Ms|Dr|Prof|Sr|Jr|St|vs|etc|e\\.g|i\\.e)|\\b[A-Z])\\.$")
    private val enumerationPrefix = Regex("^\\(?\\d+(?:\\.\\d+)*[.)]$")
    private val numberedHeading = Regex("^(?:\\d+(?:\\.\\d+)*[.)]?|[IVXLC]+[.)])\\s+\\p{Lu}.*")
    private val namedHeading = Regex("(?i)^(?:chapter|section|topic|part)\\s+(?:\\d+|[ivxlc]+|one|two|three|four|five|six|seven|eight|nine|ten)\\b.*")

    fun segment(text: String, sourcePage: Int? = null): List<Concept> {
        require(sourcePage == null || sourcePage >= 1) { "Source pages start at 1." }
        return segmentLines(lines(text, sourcePage), emptyList())
    }

    /** A page break is not a concept or sentence break. PDF outlines take precedence. */
    fun segmentPages(pages: List<SourcePage>, sections: List<SourceSection> = emptyList()): List<Concept> {
        require(pages.all { it.number >= 1 } && pages.zipWithNext().all { (a, b) -> a.number < b.number }) {
            "Source pages must be in reading order."
        }
        require(sections.all { it.sourcePage >= 1 }) { "Source pages start at 1." }
        return segmentLines(pages.flatMap { lines(it.text, it.number) }, sections)
    }

    private fun lines(text: String, page: Int?): List<Line> = text.replace("\r\n", "\n")
        .replace('\r', '\n').split('\n').map { Line(it.trim(), page) }

    private fun segmentLines(lines: List<Line>, sections: List<SourceSection>): List<Concept> {
        if (lines.none { it.text.isNotBlank() }) return emptyList()
        val boundaries = mutableMapOf<Int, Boundary>()
        lines.forEachIndexed { index, line ->
            if (isHeading(lines, index)) boundaries[index] = Boundary(headingTitle(line.text), true)
        }
        sections.filter { it.title.isNotBlank() }.groupBy { it.sourcePage }.forEach { (page, pageSections) ->
            val pageIndices = lines.indices.filter { lines[it].page == page && lines[it].text.isNotBlank() }
            if (pageIndices.isEmpty()) return@forEach
            pageSections.forEach { section ->
                val matching = pageIndices.firstOrNull {
                    headingTitle(lines[it].text).equals(headingTitle(section.title), ignoreCase = true)
                }
                if (matching != null) {
                    if (!continuesSentence(lines, matching)) boundaries[matching] = Boundary(section.title.trim(), true)
                } else if (pageSections.size == 1 && pageIndices.none { it in boundaries }) {
                    // Page-only destinations cannot precede a reliable printed heading or split a sentence.
                    val start = pageIndices.first()
                    val previous = lines.subList(0, start).lastOrNull { it.text.isNotBlank() }
                    val previousEnd = previous?.text?.trimEnd('"', '\'', '”', ')', ']')?.lastOrNull()
                    if (previous == null || previousEnd in listOf('.', '!', '?')) {
                        boundaries[start] = Boundary(section.title.trim(), false)
                    }
                }
            }
        }
        val concepts = mutableListOf<Concept>()
        var title = DOCUMENT_TITLE
        var page = lines.firstOrNull { it.text.isNotBlank() }?.page
        var bodyOffset = 0
        var documentOffset = 0
        val body = mutableListOf<Line>()
        fun finish() {
            val sentences = splitSentences(body.joinToString(" ") { it.text })
            var offset = 0
            val spans = body.groupBy { it.page }.mapNotNull { (sourcePage, lines) ->
                if (sourcePage == null) null else {
                    val text = lines.joinToString(" ") { it.text }
                    SourceSpan(sourcePage, text, offset, offset + text.length).also { offset += text.length + 1 }
                }
            }
            if (sentences.isNotEmpty()) concepts += Concept(title, sentences, page, spans, bodyOffset)
            body.clear()
        }
        lines.forEachIndexed { index, line ->
            val boundary = boundaries[index]
            if (boundary != null) {
                finish()
                title = boundary.title
                page = line.page
                if (!boundary.heading) { bodyOffset = documentOffset; body += line }
            } else if (line.text.isNotBlank()) {
                if (body.isEmpty()) bodyOffset = documentOffset
                body += line
            }
            documentOffset += line.text.length + 1
        }
        finish()
        return concepts
    }

    private fun continuesSentence(lines: List<Line>, index: Int): Boolean {
        if (lines[index].text.matches(Regex("^#{1,6}\\s+.+"))) return false
        var nextIndex = index + 1
        while (nextIndex < lines.size && lines[nextIndex].text.isBlank()) nextIndex++
        return lines.getOrNull(nextIndex)?.text?.firstOrNull(Char::isLetter)?.isLowerCase() == true
    }

    private fun isHeading(lines: List<Line>, index: Int): Boolean {
        val text = lines[index].text
        if (text.isBlank() || text.length > 120 || text.none(Char::isLetter)) return false
        if (text.startsWith("#") && text.matches(Regex("^#{1,6}\\s+.+"))) return true
        if (text.last() in ".!?;,") return false
        var nextIndex = index + 1
        while (nextIndex < lines.size && lines[nextIndex].text.isBlank()) nextIndex++
        val next = lines.getOrNull(nextIndex) ?: return false
        // PDF line wraps and page breaks can split a sentence after a name, acronym or numbered phrase.
        if (next.text.firstOrNull(Char::isLetter)?.isLowerCase() == true) return false
        if (namedHeading.matches(text) || numberedHeading.matches(text)) return true
        val words = text.removeSuffix(":").split(whitespace)
        if (words.size > 12) return false
        val letters = text.filter(Char::isLetter)
        if (letters.length >= 3 && letters.all(Char::isUpperCase)) return true
        if (next.text.none { it in ".!?" }) return false
        val separateBlock = (index == 0 || lines[index - 1].text.isBlank()) &&
            (index + 1 < lines.size && lines[index + 1].text.isBlank())
        return separateBlock
    }

    private fun headingTitle(text: String): String = text.replace(Regex("^#{1,6}\\s+"), "")
        .removeSuffix(":").trim()

    /** One result is one complete sentence. Speech buffer limits belong to Narrator. */
    fun splitSentences(text: String): List<String> {
        val clean = text.replace(whitespace, " ").trim()
        if (clean.isEmpty()) return emptyList()
        val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH)
        iterator.setText(clean)
        val result = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        var pending = ""
        while (end != BreakIterator.DONE) {
            val line = clean.substring(start, end).trim()
            pending = if (pending.isEmpty()) line else "$pending $line"
            if (!abbreviation.containsMatchIn(pending) && !enumerationPrefix.matches(pending)) {
                result += pending
                pending = ""
            }
            start = end
            end = iterator.next()
        }
        if (pending.isNotEmpty()) result += pending
        return result
    }
}
