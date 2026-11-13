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
    fun testTrailingDashesNormalizedWithoutSpuriousPunctuation() {
        val dashEnd = "This clause ended with a dash -"
        val preprocessed = frontend.preprocess(dashEnd, "en")
        assertEquals("<en>This clause ended with a dash.</en>", preprocessed)

        val emDashEnd = "This clause ended with an em dash —"
        assertEquals("<en>This clause ended with an em dash.</en>", frontend.preprocess(emDashEnd, "en"))

        val dashWithPunct = "This clause had punctuation before dash. -"
        assertEquals("<en>This clause had punctuation before dash.</en>", frontend.preprocess(dashWithPunct, "en"))
    }

    @Test
    fun testSplitStreamClausesUnder280Chars() {
        // Multi-clause sentence under 280 chars must be split at natural prosodic boundaries
        val text = "In machine learning and neural speech synthesis, natural sounding audio requires deep generative models."
        val chunks = SupertonicTextFrontend.splitStreamClauses(text, minClauseLen = 24, maxLen = 280)
        assertEquals(2, chunks.size)
        assertEquals("In machine learning and neural speech synthesis,", chunks[0])
        assertEquals("natural sounding audio requires deep generative models.", chunks[1])
    }

    @Test
    fun testSplitStreamClausesSemicolonAndDashes() {
        val text = "Generative audio is powerful; running it offline requires careful pipelining — especially on phones."
        val chunks = SupertonicTextFrontend.splitStreamClauses(text, minClauseLen = 20, maxLen = 280)
        assertTrue(chunks.size >= 2)
        assertEquals("Generative audio is powerful;", chunks[0])
    }

    @Test
    fun testSplitStreamClausesShortSentenceUnchanged() {
        val short = "Short sentence."
        val chunks = SupertonicTextFrontend.splitStreamClauses(short, minClauseLen = 24, maxLen = 280)
        assertEquals(1, chunks.size)
        assertEquals("Short sentence.", chunks[0])
    }

    @Test
    fun testInitialStreamingChunkSplitting() {
        val text = "Anchor Reader provides private offline document reading. It streams high fidelity speech without external cloud dependencies."
        val chunks = SupertonicTextFrontend.splitStreamClauses(text, initialStreamingChunk = true)

        assertTrue("Must have multiple chunks", chunks.size >= 2)
        val first = chunks[0]
        val wordCount = first.split(Regex("\\s+")).size
        assertTrue("Initial chunk must be 3..5 words, was $wordCount ('$first')", wordCount in 3..5)
        assertTrue("Initial chunk must be 15..30 chars, was ${first.length} ('$first')", first.length in 15..30)
        assertEquals("Anchor Reader provides", first)
    }

    @Test
    fun testInitialStreamingChunkWithPunctuation() {
        val text = "Yes, dear friend, offline speech synthesis is running completely on device."
        val chunks = SupertonicTextFrontend.splitStreamClauses(text, initialStreamingChunk = true)
        val first = chunks[0]
        val wordCount = first.split(Regex("\\s+")).size
        assertTrue("Initial chunk must be 3..5 words, was $wordCount", wordCount in 3..5)
        assertTrue("Initial chunk must be 15..30 chars, was ${first.length}", first.length in 15..30)
        assertEquals("Yes, dear friend,", first)
    }

    @Test
    fun testProgressiveClauseHorizonBoundsClause1AndClause2Plus() {
        // Long sentence with no punctuation after initial words to test progressive horizon bounds
        val text = "Anchor Reader provides seamless private document reading across complex academic papers and technical articles without ever sending your personal library data to external servers during speech synthesis."
        val chunks = SupertonicTextFrontend.splitStreamClauses(text, initialStreamingChunk = true)

        assertTrue("Must split into at least 3 progressive clauses", chunks.size >= 3)
        // Clause 0: 3..5 words, 15..30 chars
        val clause0Words = chunks[0].split(Regex("\\s+")).size
        assertTrue("Clause 0 must be 3..5 words, was $clause0Words", clause0Words in 3..5)
        assertTrue("Clause 0 must be 15..30 chars, was ${chunks[0].length}", chunks[0].length in 15..30)

        // Clause 1 (bridge clause): <= 75 chars
        assertTrue(
            "Bridge clause (Clause 1) must be <= ${SupertonicTextFrontend.BRIDGE_CLAUSE_MAX_LEN} chars, was ${chunks[1].length} ('${chunks[1]}')",
            chunks[1].length <= SupertonicTextFrontend.BRIDGE_CLAUSE_MAX_LEN
        )

        // Clause 2+: <= 160 chars
        for (i in 2 until chunks.size) {
            assertTrue(
                "Clause $i must be <= ${SupertonicTextFrontend.STREAM_CLAUSE_MAX_LEN} chars, was ${chunks[i].length} ('${chunks[i]}')",
                chunks[i].length <= SupertonicTextFrontend.STREAM_CLAUSE_MAX_LEN
            )
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
