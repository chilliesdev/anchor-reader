package com.anchorreader

import org.junit.Assert.*
import org.junit.Test

class SentenceDisplayTest {
    @Test fun slicesRetainWhitespacePunctuationAndUnicodeAndRemainBounded() {
        val sentence = "  A café, e\u0301 and 😀!\n".repeat(30) + "unbroken".repeat(40) + "  "
        val parts = requireNotNull(splitSentenceDisplay(sentence) { it.codePointCount(0, it.length) <= 27 })
        assertEquals(sentence, parts.joinToString(""))
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.codePointCount(0, it.length) <= 27 })
        parts.forEach {
            assertFalse(Character.isLowSurrogate(it.first()))
            assertFalse(Character.isHighSurrogate(it.last()))
            assertFalse(it.startsWith("\u0301"))
        }
    }

    @Test fun diagramAndSpacingThenFontAdaptBeforeSplitting() {
        val calls = mutableListOf<String>()
        val display = requireNotNull(fitSentenceDisplay("A complete sentence.", 30, 150) { _, size, art, gap, parts ->
            calls += "$size:$art:$gap:$parts"
            art == 112 && gap == 8 && size <= 23
        })
        assertEquals(listOf("A complete sentence."), display.parts)
        assertEquals(23, display.fontSize)
        assertEquals(112, display.diagramHeight)
        assertEquals(8, display.gap)
        assertTrue(calls.none { it.endsWith(":true") })
    }

    @Test fun textOnlyHasNoArtworkAndWholeSentenceWinsBeforeParts() {
        val display = requireNotNull(fitSentenceDisplay("Text only.", 27, 0) { _, size, art, _, parts ->
            art == 0 && size <= 25 && !parts
        })
        assertEquals(listOf("Text only."), display.parts)
        assertEquals(25, display.fontSize)
        assertEquals(0, display.diagramHeight)
    }

    @Test fun overflowHidesDiagramUsesReadableFloorAndReservesPartControls() {
        val text = "One full sentence with punctuation; ".repeat(30)
        val display = requireNotNull(fitSentenceDisplay(text, 27, 150) { candidate, size, art, _, parts ->
            size == 16 && art == 0 && candidate.length <= if (parts) 50 else 60
        })
        assertEquals(16, display.fontSize)
        assertEquals(0, display.diagramHeight)
        assertTrue(display.parts.size > 1)
        assertTrue(display.parts.all { it.length <= 50 })
        assertEquals(text, display.parts.joinToString(""))
    }

    @Test fun rejectedMinimumCharacterDoesNotBecomeAClaimedFittingPart() {
        assertNull(splitSentenceDisplay("A complete sentence.") { false })
        assertNull(fitSentenceDisplay("A complete sentence.", 27, 0) { _, _, _, _, _ -> false })
        assertNull(splitSentenceDisplay("AB") { it == "A" })
    }
}
