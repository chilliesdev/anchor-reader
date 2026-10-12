package com.anchorreader.data

data class PracticeSummary(val ready: Int, val answered: Int, val concepts: Int) {
    val action: String get() = when {
        answered == 0 -> "Start practice"
        answered < ready -> "Continue practice"
        else -> "Practice again"
    }
}

fun savedQuestions(book: Book, practice: PracticeRecord): List<RecallQuestion> =
    if (book.isSample) SampleLesson.questions
    else if (practice.documentVersion == book.documentVersion) practice.questions else emptyList()

fun savedAnswer(book: Book, progress: Progress, practice: PracticeRecord, question: RecallQuestion): Outcome? =
    if (book.isSample) progress.answers[SampleLesson.questions.indexOf(question)]
    else if (practice.documentVersion == book.documentVersion) practice.answer(question) else null

fun practiceSummary(book: Book, progress: Progress, practice: PracticeRecord): PracticeSummary {
    val questions = savedQuestions(book, practice)
    return PracticeSummary(questions.size, questions.count { savedAnswer(book, progress, practice, it) != null },
        questions.map { it.unitIndex }.distinct().size)
}

fun practiceStartIndex(book: Book, progress: Progress, practice: PracticeRecord, questions: List<RecallQuestion>): Int =
    questions.indexOfFirst { savedAnswer(book, progress, practice, it) == null }.coerceAtLeast(0)
