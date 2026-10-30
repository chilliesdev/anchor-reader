package com.anchorreader.supertonic

import org.junit.Assert.*
import org.junit.Test

class SupertonicTextFrontendTest {

    private val dummyIndexer = IntArray(65536) { idx ->
        when (idx.toChar()) {
            'a' -> 1
            'b' -> 2
            'c' -> 3
            ' ' -> 4
            '.' -> 5
            '<' -> 6
            '>' -> 7
            '/' -> 8
            'e' -> 9
            'n' -> 10
            else -> 0
        }
    }
    private val frontend = SupertonicTextFrontend(dummyIndexer)

    @Test
    fun testPreprocessingNfkdAndTypographicReplacements() {
        // Smart quotes, em dashes, en dashes, whitespace
        val input = "“Hello world” – this is ‘AnchorReader’—fast & clear."
        val preprocessed = frontend.preprocess(input, "en")

        assertTrue("Must wrap in language tags", preprocessed.startsWith("<en>") && preprocessed.endsWith("</en>"))
        val inner = preprocessed.removePrefix("<en>").removeSuffix("</en>")
        assertFalse("Must not have curly double quotes", inner.contains("“") || inner.contains("”"))
        assertFalse("Must not have curly single quotes", inner.contains("‘") || inner.contains("’"))
        assertFalse("Must not have em-dash or en-dash", inner.contains("–") || inner.contains("—"))
        assertTrue("Must have standardized quote", inner.contains("\"Hello world\""))
        assertTrue("Must have standardized dash", inner.contains("-"))
    }

    @Test
    fun testEndingPunctuationAdded() {
        val input = "Sentence without ending punctuation"
        val preprocessed = frontend.preprocess(input, "en")
        assertEquals("<en>Sentence without ending punctuation.</en>", preprocessed)

        val withPunct = "Sentence with exclamation!"
        assertEquals("<en>Sentence with exclamation!</en>", frontend.preprocess(withPunct, "en"))
    }

    @Test
    fun testSpaceBeforePunctuationRemoved() {
        val input = "Hello , world . How are you ?"
        val preprocessed = frontend.preprocess(input, "en")
        assertEquals("<en>Hello, world. How are you?</en>", preprocessed)
    }

    @Test
    fun testClauseChunkingUnder280Chars() {
        val shortSentence = "This is a short sentence."
        val chunks = SupertonicTextFrontend.splitClauses(shortSentence, 280)
        assertEquals(1, chunks.size)
        assertEquals(shortSentence, chunks[0])

        // Long text over 280 chars
        val longSentence = "In machine learning and neural speech synthesis, natural sounding audio requires deep generative models such as flow matching vector estimators; however, running these on-device requires extreme optimization, quantized INT8 weights, efficient memory management, and careful pipelining, so that mobile processors can sustain real-time playback without overheating or dropping audio frames."
        assertTrue(longSentence.length > 280)

        val splitChunks = SupertonicTextFrontend.splitClauses(longSentence, 280)
        assertTrue("Must be split into multiple chunks", splitChunks.size > 1)
        for (chunk in splitChunks) {
            assertTrue("Each chunk must be <= 280 chars but was ${chunk.length}", chunk.length <= 280)
            assertTrue("Chunk must not be empty", chunk.isNotBlank())
        }
    }

    @Test
    fun testEncodingWithIndexer() {
        val text = "<en>abc</en>"
        val (tokens, mask) = frontend.encode(text)

        assertEquals(text.length, tokens.size)
        assertEquals(text.length, mask.size)

        // '<' = 6, 'e' = 9, 'n' = 10, '>' = 7, 'a' = 1, 'b' = 2, 'c' = 3, '<' = 6, '/' = 8, 'e' = 9, 'n' = 10, '>' = 7
        val expected = longArrayOf(6, 9, 10, 7, 1, 2, 3, 6, 8, 9, 10, 7)
        assertArrayEquals(expected, tokens)
        for (m in mask) {
            assertEquals(1.0f, m, 0.0001f)
        }
    }
}
