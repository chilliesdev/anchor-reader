package com.anchorreader.data

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.util.Locale

/** Font contrast is section evidence; title case alone is not. */
internal class PdfHeadingExtractor : PDFTextStripper() {
    private data class Style(val font: String, val size: Float)
    private data class PrintedLine(val text: String, val page: Int, val styles: Map<Style, Int>, val relativeY: Float)
    private val printed = mutableListOf<PrintedLine>()
    private var sourcePage = 1
    private val line = StringBuilder()
    private val styles = mutableMapOf<Style, Int>()
    private var relativeY = 1f

    init { sortByPosition = true }

    fun readPage(document: PDDocument, number: Int): String {
        sourcePage = number
        startPage = number
        endPage = number
        val text = getText(document).trim()
        finishLine()
        return text
    }

    override fun writeString(text: String, positions: MutableList<TextPosition>) {
        line.append(text)
        positions.forEach { position ->
            if (position.pageHeight > 0) relativeY = minOf(relativeY, position.yDirAdj / position.pageHeight)
            val letters = position.unicode.count(Char::isLetterOrDigit)
            if (letters > 0) {
                val style = Style(position.font.name.orEmpty(), position.fontSizeInPt)
                styles[style] = (styles[style] ?: 0) + letters
            }
        }
        super.writeString(text, positions)
    }

    override fun writeWordSeparator() { line.append(' '); super.writeWordSeparator() }
    override fun writeLineSeparator() { finishLine(); super.writeLineSeparator() }
    override fun writeParagraphEnd() { finishLine(); super.writeParagraphEnd() }

    private fun finishLine() {
        val text = line.toString().trim()
        if (text.isNotEmpty()) printed += PrintedLine(text, sourcePage, styles.toMap(), relativeY)
        line.clear()
        styles.clear()
        relativeY = 1f
    }

    fun headings(): List<TextSegmenter.SourceSection> {
        val totals = mutableMapOf<Style, Int>()
        printed.filter { it.relativeY < .9f }.forEach { row -> row.styles.forEach { (style, count) -> totals[style] = (totals[style] ?: 0) + count } }
        // Short sections can contain more heading glyphs than body glyphs. Use sentence lines first.
        // Small bottom-margin text is retained as source, but it is not body-font or heading evidence.
        val sentenceStyles = mutableMapOf<Style, Int>()
        printed.filter { row ->
            row.relativeY < .9f && row.text.trimEnd('"', '\'', '”', ')', ']').lastOrNull() in listOf('.', '!', '?')
        }.forEach { row -> row.styles.keys.forEach { style -> sentenceStyles[style] = (sentenceStyles[style] ?: 0) + 1 } }
        val regularSentenceStyles = sentenceStyles.filterKeys { !bold(it.font) }
        val evidence = regularSentenceStyles.ifEmpty { sentenceStyles }
        val body = evidence.maxWithOrNull(compareBy({ it.value }, { it.key.size }))?.key
            ?: totals.filterKeys { !bold(it.font) }.maxByOrNull { it.value }?.key
            ?: totals.maxByOrNull { it.value }?.key ?: return emptyList()
        return printed.mapNotNull { row ->
            val text = row.text
            if (row.relativeY >= .9f || text.length > 120 || text.split(Regex("\\s+")).size > 12 || text.lastOrNull() in listOf('.', '!', '?', ';', ',')) return@mapNotNull null
            val letters = row.styles.values.sum()
            val distinct = row.styles.filterKeys { style ->
                style.size >= body.size * 1.2f || (bold(style.font) && !bold(body.font) && style.size >= body.size)
            }.values.sum()
            if (letters == 0 || distinct.toFloat() / letters < .8f) null
            else TextSegmenter.SourceSection(text, row.page)
        }.distinct()
    }

    private fun bold(name: String): Boolean = Regex("bold|black|demi|heavy").containsMatchIn(name.lowercase(Locale.ENGLISH))
}
