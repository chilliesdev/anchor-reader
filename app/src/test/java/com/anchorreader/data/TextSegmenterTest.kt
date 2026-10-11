package com.anchorreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSegmenterTest {
    @Test
    fun `headings form concepts and paragraphs remain in their topic`() {
        val source = """
            # Save a response

            A cache saves responses. It does not save everything!

            A missing file needs a network connection? Only if it is not local.

            # Repair a package

            Download the missing file. Verify it before marking the package ready.
        """.trimIndent()
        val units = TextSegmenter.segment(source, 8)
        assertEquals(listOf("Save a response", "Repair a package"), units.map { it.title })
        assertEquals(listOf(4, 2), units.map { it.sentences.size })
        assertEquals("A cache saves responses.", units.first().sentences.first())
        assertEquals("Only if it is not local.", units.first().sentences.last())
        assertTrue(units.all { it.sourcePage == 8 })
    }

    @Test
    fun `plain standalone section titles work without markdown`() {
        val units = TextSegmenter.segment("The request comes first\n\nA browser asks for a page. It waits for a response.\n\nStore request and response pairs\n\nThe cache keeps the pair.")
        assertEquals(listOf("The request comes first", "Store request and response pairs"), units.map { it.title })
        assertEquals(listOf(2, 1), units.map { it.sentences.size })
    }

    @Test
    fun `headingless paragraphs use one explicit full document concept`() {
        val source = "A cache saves responses. It does not save everything!\n\nA missing file needs a network connection? Only if it is not local."
        val unit = TextSegmenter.segment(source, 8).single()
        assertEquals("Full document", unit.title)
        assertEquals(source.replace(Regex("\\s+"), " "), unit.sentences.joinToString(" "))
        assertEquals(8, unit.sourcePage)
    }

    @Test
    fun `a topic is not split at the old six sentence limit`() {
        val sentences = (1..20).map { "Sentence $it has source evidence." }
        val unit = TextSegmenter.segment("# Evidence\n\n${sentences.joinToString(" ")}").single()
        assertEquals("Evidence", unit.title)
        assertEquals(sentences, unit.sentences)
    }

    @Test
    fun `long sentence retains one identity and all source words`() {
        val sentence = (1..500).joinToString(" ") { "word$it" } + "."
        val unit = TextSegmenter.segment("# Long sentence\n\n$sentence A short next sentence.").single()
        assertEquals(listOf(sentence, "A short next sentence."), unit.sentences)
    }

    @Test
    fun `long token is source text rather than a false speech buffer sentence`() {
        val source = "x".repeat(5_000)
        assertEquals(listOf(source), TextSegmenter.segment(source).single().sentences)
    }

    @Test
    fun `topic and complete sentence can span PDF pages`() {
        val units = TextSegmenter.segmentPages(listOf(
            TextSegmenter.SourcePage(2, "Chapter 1: Cache\nA cache saves responses. A long source sentence starts"),
            TextSegmenter.SourcePage(3, "on the earlier page and ends here. Another sentence stays in this topic.\n2. Network Requests\nA request reaches the network. It gets a response."),
        ))
        assertEquals(listOf("Chapter 1: Cache", "2. Network Requests"), units.map { it.title })
        assertEquals(listOf(2, 3), units.map { it.sourcePage })
        assertEquals(listOf(
            "A cache saves responses.",
            "A long source sentence starts on the earlier page and ends here.",
            "Another sentence stays in this topic.",
        ), units.first().sentences)
        assertEquals(2, units.last().sentences.size)
    }

    @Test
    fun `outline resolves multiple sentence case titles on one page`() {
        val units = TextSegmenter.segmentPages(
            listOf(TextSegmenter.SourcePage(1, "why a cache helps\nThe cache saves a response.\nwhen it fails\nThe file can be missing.")),
            listOf(TextSegmenter.SourceSection("why a cache helps", 1), TextSegmenter.SourceSection("when it fails", 1)),
        )
        assertEquals(listOf("why a cache helps", "when it fails"), units.map { it.title })
        assertEquals(listOf("The cache saves a response.", "The file can be missing."), units.map { it.sentences.single() })
    }

    @Test
    fun `outline without a printed title starts on destination page`() {
        val units = TextSegmenter.segmentPages(
            listOf(TextSegmenter.SourcePage(1, "First topic begins. It continues."), TextSegmenter.SourcePage(2, "Second topic begins.")),
            listOf(TextSegmenter.SourceSection("Topic A", 1), TextSegmenter.SourceSection("Topic B", 2)),
        )
        assertEquals(listOf("Topic A", "Topic B"), units.map { it.title })
        assertEquals(listOf(1, 2), units.map { it.sourcePage })
        assertEquals(listOf(2, 1), units.map { it.sentences.size })
    }

    @Test
    fun `unmatched bookmark defers to reliable printed heading and preserves preceding sentence`() {
        val units = TextSegmenter.segmentPages(
            listOf(
                TextSegmenter.SourcePage(1, "# First topic\nFirst sentence starts"),
                TextSegmenter.SourcePage(2, "and ends here.\n# Second topic\nSecond sentence."),
            ),
            listOf(TextSegmenter.SourceSection("First topic", 1), TextSegmenter.SourceSection("Second topic (bookmark)", 2)),
        )
        assertEquals(listOf("First topic", "Second topic"), units.map { it.title })
        assertEquals(listOf("First sentence starts and ends here.", "Second sentence."), units.map { it.sentences.single() })
        assertEquals(listOf(1, 2), units.map { it.sourcePage })
    }

    @Test
    fun `page only bookmark cannot split an unfinished sentence`() {
        val unit = TextSegmenter.segmentPages(
            listOf(TextSegmenter.SourcePage(1, "# Travel\nI visited"), TextSegmenter.SourcePage(2, "New York\nlast year.")),
            listOf(TextSegmenter.SourceSection("A new bookmark", 2)),
        ).single()
        assertEquals("Travel", unit.title)
        assertEquals(listOf("I visited New York last year."), unit.sentences)
    }

    @Test
    fun `headingless PDF uses one concept across its page breaks`() {
        val pages = listOf(TextSegmenter.SourcePage(2, "The first sentence starts"), TextSegmenter.SourcePage(3, "on the earlier page. The next sentence ends here."))
        val unit = TextSegmenter.segmentPages(pages).single()
        assertEquals("Full document", unit.title)
        assertEquals(2, unit.sourcePage)
        assertEquals(listOf("The first sentence starts on the earlier page.", "The next sentence ends here."), unit.sentences)
    }

    @Test
    fun `common title abbreviations stay with their sentence`() {
        val sentences = TextSegmenter.splitSentences("Dr. Ada wrote the paper. Mr. Smith reviewed it.")
        assertEquals(listOf("Dr. Ada wrote the paper.", "Mr. Smith reviewed it."), sentences)
    }

    @Test
    fun `proper names acronyms and numbered continuations retain every source word`() {
        listOf(
            "Alice Smith\nworks at the clinic." to "Alice Smith works at the clinic.",
            "NASA\ntracks the launch." to "NASA tracks the launch.",
            "1. Review the source\nbefore you act." to "1. Review the source before you act.",
        ).forEach { (source, expected) ->
            val unit = TextSegmenter.segment(source).single()
            assertEquals("Full document", unit.title)
            assertEquals(listOf(expected), unit.sentences)
        }
    }

    @Test
    fun `page continuation is not a new concept at a title case place name`() {
        val unit = TextSegmenter.segmentPages(listOf(
            TextSegmenter.SourcePage(1, "# Travel\nI visited"),
            TextSegmenter.SourcePage(2, "New York\nlast year."),
        )).single()
        assertEquals("Travel", unit.title)
        assertEquals(listOf("I visited New York last year."), unit.sentences)
        assertEquals(1, unit.sourcePage)
    }

    @Test
    fun `unseparated title case source text is not invented heading evidence`() {
        val unit = TextSegmenter.segment("Alice Smith\nWorks at the clinic.").single()
        assertEquals("Full document", unit.title)
        assertEquals("Alice Smith Works at the clinic.", unit.sentences.joinToString(" "))
    }

    @Test
    fun `section hints do not remove a wrapped subject from its sentence`() {
        val unit = TextSegmenter.segmentPages(
            listOf(TextSegmenter.SourcePage(1, "Alice Smith\nworks at the clinic.")),
            listOf(TextSegmenter.SourceSection("Alice Smith", 1)),
        ).single()
        assertEquals(listOf("Alice Smith works at the clinic."), unit.sentences)
    }

    @Test
    fun `line breaks normalize without making paragraph concepts`() {
        val units = TextSegmenter.segment("First line\r\ncontinues here.\r\n\r\nSecond paragraph remains in this topic.")
        assertEquals(1, units.size)
        assertEquals(listOf("First line continues here.", "Second paragraph remains in this topic."), units.single().sentences)
    }

    @Test
    fun `empty input produces no empty concepts`() {
        assertTrue(TextSegmenter.segment(" \n\n\t\r\n").isEmpty())
        assertFalse(TextSegmenter.segment("A complete source sentence.").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid source page is rejected`() {
        TextSegmenter.segment("A source sentence.", 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PDF pages out of order are rejected`() {
        TextSegmenter.segmentPages(listOf(TextSegmenter.SourcePage(2, "A sentence."), TextSegmenter.SourcePage(1, "Another sentence.")))
    }
}
