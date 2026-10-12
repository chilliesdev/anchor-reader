package com.anchorreader

import java.text.BreakIterator
import java.util.Locale

/** Display slices only. The reading position and narration always use the complete sentence. */
data class SentenceDisplay(val parts: List<String>, val fontSize: Int, val diagramHeight: Int, val gap: Int)

internal fun fitSentenceDisplay(
    sentence: String,
    preferredSize: Int,
    diagramHeight: Int,
    fits: (text: String, fontSize: Int, diagramHeight: Int, gap: Int, hasParts: Boolean) -> Boolean,
): SentenceDisplay? {
    val diagrams = if (diagramHeight > 0) listOf(diagramHeight, minOf(diagramHeight, 112)).distinct() else listOf(0)
    for (height in diagrams) {
        if (fits(sentence, preferredSize, height, 16, false)) return SentenceDisplay(listOf(sentence), preferredSize, height, 16)
    }
    val compactDiagram = diagrams.last()
    for (size in preferredSize downTo 16) {
        if (fits(sentence, size, compactDiagram, 8, false)) return SentenceDisplay(listOf(sentence), size, compactDiagram, 8)
    }
    if (diagramHeight > 0) {
        for (size in preferredSize downTo 16) {
            if (fits(sentence, size, 0, 8, false)) return SentenceDisplay(listOf(sentence), size, 0, 8)
        }
    }
    val parts = splitSentenceDisplay(sentence) { fits(it, 16, 0, 8, true) } ?: return null
    return SentenceDisplay(parts, 16, 0, 8)
}

/** Retain every source character. Prefer word boundaries; never split a Unicode character. */
internal fun splitSentenceDisplay(sentence: String, fits: (String) -> Boolean): List<String>? {
    if (sentence.isEmpty()) return if (fits(sentence)) listOf(sentence) else null
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(sentence) }
    val boundaries = mutableListOf<Int>()
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE) {
        boundaries += boundary
        boundary = iterator.next()
    }
    val parts = mutableListOf<String>()
    var startIndex = 0
    while (startIndex < boundaries.lastIndex) {
        val start = boundaries[startIndex]
        var low = startIndex + 1
        var high = boundaries.lastIndex
        var best = startIndex
        while (low <= high) {
            val middle = (low + high) / 2
            if (fits(sentence.substring(start, boundaries[middle]))) { best = middle; low = middle + 1 }
            else high = middle - 1
        }
        if (best == startIndex) return null
        if (best < boundaries.lastIndex) {
            // Avoid needlessly short slices when an unbroken word follows earlier whitespace.
            val wordBoundary = (best downTo startIndex + 1).firstOrNull {
                sentence[boundaries[it] - 1].isWhitespace() && boundaries[it] - start >= (boundaries[best] - start) / 2
            }
            if (wordBoundary != null && fits(sentence.substring(start, boundaries[wordBoundary]))) best = wordBoundary
        }
        parts += sentence.substring(start, boundaries[best])
        startIndex = best
    }
    return parts
}
