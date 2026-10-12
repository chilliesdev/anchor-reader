package com.anchorreader.data

import org.junit.Assert.*
import org.junit.Test

class PracticeSummaryTest {
    @Test fun sampleCountsWrongAndSkippedAnswersWithoutReadingVisits() {
        val book = SampleLesson.book
        val record = PracticeRecord(book.documentVersion)
        val progress = Progress(answers = mapOf(0 to Outcome.WRONG, 1 to Outcome.SKIPPED, 99 to Outcome.CORRECT))
        val summary = practiceSummary(book, progress, record)
        assertEquals(PracticeSummary(3, 2, 3), summary)
        assertEquals("Continue practice", summary.action)
        assertEquals(2, practiceStartIndex(book, progress, record, SampleLesson.questions))
        assertTrue(progress.visited.isEmpty())
        val complete = progress.copy(answers = progress.answers + (2 to Outcome.CORRECT))
        assertEquals("Practice again", practiceSummary(book, complete, record).action)
        assertEquals(0, practiceStartIndex(book, complete, record, SampleLesson.questions))
        assertEquals("Start practice", practiceSummary(book, Progress(), record).action)
    }

    @Test fun pdfCountsOnlySavedQuestionsAndMatchingAnswerVersionsAndDistinctConcepts() {
        val book = SampleLesson.book.copy(id = "pdf", isSample = false)
        val questions = listOf(SampleLesson.questions[0].copy(id = "a", version = "1"),
            SampleLesson.questions[0].copy(id = "b", version = "2"), SampleLesson.questions[1].copy(id = "c", version = "1"))
        val record = PracticeRecord(book.documentVersion, questions, mapOf("a@1" to Outcome.WRONG, "b@1" to Outcome.CORRECT,
            "c@1" to Outcome.SKIPPED, "removed@1" to Outcome.CORRECT))
        assertEquals(PracticeSummary(3, 2, 2), practiceSummary(book, Progress(visited = setOf(0)), record))
        assertEquals(1, practiceStartIndex(book, Progress(), record, questions))
        assertEquals(PracticeSummary(0, 0, 0), practiceSummary(book, Progress(), record.copy(documentVersion = "old")))
    }
}
