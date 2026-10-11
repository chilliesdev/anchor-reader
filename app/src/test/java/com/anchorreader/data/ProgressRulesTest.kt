package com.anchorreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressRulesTest {
    @Test
    fun `invalid restored positions clamp without changing valid answer outcomes`() {
        val book = SampleLesson.book
        val invalid = Progress(98, -4, setOf(-1, 0, 3, 98),
            mapOf(-1 to Outcome.CORRECT, 0 to Outcome.WRONG, 2 to Outcome.SKIPPED, 3 to Outcome.CORRECT, 98 to Outcome.CORRECT))
        val restored = ProgressRules.normalize(book, invalid)
        assertEquals(3, restored.unitIndex)
        assertEquals(0, restored.sentenceIndex)
        assertEquals(setOf(0, 3), restored.visited)
        assertEquals(mapOf(0 to Outcome.WRONG, 2 to Outcome.SKIPPED), restored.answers)
    }

    @Test
    fun `imported books cannot restore generated question outcomes`() {
        val imported = SampleLesson.book.copy(id = "imported-book", isSample = false)
        val progress = Progress(visited = setOf(0, 1), answers = mapOf(0 to Outcome.CORRECT, 1 to Outcome.SKIPPED))
        val restored = ProgressRules.normalize(imported, progress)
        assertEquals(progress.visited, restored.visited)
        assertTrue(restored.answers.isEmpty())
    }

    @Test
    fun `completion does not imply correct answers`() {
        val progress = Progress(3, 2, setOf(0, 1, 2, 3),
            mapOf(0 to Outcome.WRONG, 1 to Outcome.SKIPPED, 2 to Outcome.CORRECT), true)
        val restored = ProgressRules.normalize(SampleLesson.book, progress)
        assertTrue(restored.completed)
        assertEquals(progress.answers, restored.answers)
        assertEquals(1, restored.answers.values.count { it == Outcome.CORRECT })
    }

    @Test
    fun `correct wrong and unanswered remain distinct`() {
        val question = SampleLesson.questions.first()
        assertEquals(Outcome.CORRECT, ProgressRules.answer(question, 0))
        assertEquals(Outcome.WRONG, ProgressRules.answer(question, 1))
        assertEquals(Outcome.SKIPPED, ProgressRules.answer(question, null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid answer cannot count as a learner attempt`() {
        ProgressRules.answer(SampleLesson.questions.first(), 20)
    }

    @Test
    fun `empty book cannot restore an impossible playback position`() {
        val book = SampleLesson.book.copy(units = emptyList())
        assertEquals(Progress(), ProgressRules.normalize(book, Progress(2, 4, setOf(2), completed = true)))
    }
}
