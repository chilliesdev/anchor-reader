package com.anchorreader

import com.anchorreader.data.*
import org.junit.Assert.*
import org.junit.Test

class ReaderResultScopeTest {
    @Test
    fun sampleSessionResultsUseOriginalAnswerIndexesAndKeepActualVisits() {
        val questions = SampleLesson.questions.drop(1)
        val progress = Progress(1, 1, setOf(0, 1, 2), mapOf(0 to Outcome.CORRECT, 1 to Outcome.WRONG, 2 to Outcome.SKIPPED))
        val state = ReaderState(books = listOf(SampleLesson.book), loading = false, screen = Screen.FINISH,
            progress = mapOf(SampleLesson.book.id to progress), practiceSession = questions)
        assertEquals(questions, state.questions)
        assertEquals(mapOf(0 to Outcome.WRONG, 1 to Outcome.SKIPPED), state.resultAnswers)
        assertEquals(setOf(0, 1, 2), state.currentProgress.visited)
        assertFalse(state.currentProgress.completed)
        assertEquals(SampleLesson.questions, state.copy(screen = Screen.PLAYER).questions)
        assertEquals(progress.answers, state.copy(practiceSession = emptyList()).resultAnswers)
    }

    @Test
    fun importedSessionExcludesOtherConceptAnswersAndQuestionVersions() {
        val book = SampleLesson.book.copy(id = "imported", isSample = false)
        val first = SampleLesson.questions[0].copy(id = "first", version = "v1")
        val second = SampleLesson.questions[1].copy(id = "second", version = "v2")
        val questions = listOf(first, second)
        val record = PracticeRecord(book.documentVersion, questions,
            mapOf(answerKey(first) to Outcome.CORRECT, "second@v1" to Outcome.CORRECT, answerKey(second) to Outcome.SKIPPED))
        val state = ReaderState(books = listOf(book), activeId = book.id, loading = false, screen = Screen.CHECK,
            practice = mapOf(book.id to record), practiceSession = listOf(second))
        assertEquals(listOf(second), state.questions)
        assertEquals(mapOf(0 to Outcome.SKIPPED), state.copy(screen = Screen.FINISH).resultAnswers)
        assertEquals(questions, state.copy(screen = Screen.PLAYER).questions)
        assertEquals(mapOf(0 to Outcome.CORRECT, 1 to Outcome.SKIPPED), state.copy(practiceSession = emptyList()).resultAnswers)
        assertTrue(state.currentProgress.visited.isEmpty())
    }
}
