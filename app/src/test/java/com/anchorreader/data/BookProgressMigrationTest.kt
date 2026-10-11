package com.anchorreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookProgressMigrationTest {
    @Test
    fun `paragraph concepts merge but resume at the same complete sentence`() {
        val source = "# Cache\n\nFirst sentence.\n\nSecond sentence. Third sentence.\n\n# Network\n\nFourth sentence."
        val previous = book(source, listOf(
            Concept("Old 1", listOf("# Cache First sentence.")),
            Concept("Old 2", listOf("Second sentence.", "Third sentence.")),
            Concept("Old 3", listOf("# Network Fourth sentence.")),
        ))
        val current = previous.copy(units = TextSegmenter.segment(source))
        val moved = BookProgressMigration.remap(previous, current, Progress(1, 1, setOf(0, 1)))
        assertEquals(0, moved.unitIndex)
        assertEquals(2, moved.sentenceIndex)
        assertEquals(setOf(0), moved.visited)
        assertEquals("Third sentence.", current.units[moved.unitIndex].sentences[moved.sentenceIndex])
    }

    @Test
    fun `repeated source sentences keep their original reading position`() {
        val source = "# First\n\nRepeat this.\n\n# Second\n\nRepeat this. Last sentence."
        val previous = book(source, listOf(
            Concept("Old 1", listOf("# First Repeat this.")),
            Concept("Old 2", listOf("# Second Repeat this.", "Last sentence.")),
        ))
        val current = previous.copy(units = TextSegmenter.segment(source))
        val moved = BookProgressMigration.remap(previous, current, Progress(1, 0, setOf(1)))
        assertEquals(1, moved.unitIndex)
        assertEquals(0, moved.sentenceIndex)
        assertEquals(setOf(1), moved.visited)
    }

    @Test
    fun `old speech buffer pieces map to the original full sentence`() {
        val beginning = (1..80).joinToString(" ") { "word$it" }
        val ending = (81..160).joinToString(" ") { "word$it" } + "."
        val source = "$beginning $ending Next sentence."
        val previous = book(source, listOf(Concept("Old", listOf(beginning, ending, "Next sentence."))))
        val current = previous.copy(units = TextSegmenter.segment(source))
        val moved = BookProgressMigration.remap(previous, current, Progress(0, 1, setOf(0), completed = true))
        assertEquals(0, moved.sentenceIndex)
        assertEquals("$beginning $ending", current.units.single().sentences[moved.sentenceIndex])
        assertTrue(moved.completed)
        assertEquals(setOf(0), moved.visited)
    }

    @Test
    fun `fallback migration keeps source page from matching saved source`() {
        val source = "# First\n\nFirst sentence.\n\n# Second\n\nSecond sentence."
        val previous = book(source, listOf(
            Concept("Old 1", listOf("# First First sentence."), 2),
            Concept("Old 2", listOf("# Second Second sentence."), 4),
        ))
        val current = previous.copy(units = TextSegmenter.segment(source))
        assertEquals(listOf(2, 4), BookProgressMigration.restoreSourcePages(previous, current).units.map { it.sourcePage })
    }

    private fun book(source: String, units: List<Concept>) = Book("migration-test", "Title", "Author", units, source)
}
