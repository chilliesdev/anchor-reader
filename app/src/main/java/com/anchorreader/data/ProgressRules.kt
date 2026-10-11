package com.anchorreader.data

object ProgressRules {
    fun normalize(book: Book, progress: Progress): Progress {
        if (book.units.isEmpty()) return Progress()
        val unit = progress.unitIndex.coerceIn(book.units.indices)
        val lastSentence = (book.units[unit].sentences.size - 1).coerceAtLeast(0)
        return progress.copy(
            unitIndex = unit,
            sentenceIndex = progress.sentenceIndex.coerceIn(0, lastSentence),
            visited = progress.visited.filter { it in book.units.indices }.toSet(),
            answers = if (book.isSample) progress.answers.filterKeys { it in SampleLesson.questions.indices } else emptyMap(),
        )
    }

    fun answer(question: RecallQuestion, selectedIndex: Int?): Outcome {
        require(question.correctIndex in question.options.indices) { "Invalid answer key." }
        if (selectedIndex == null) return Outcome.SKIPPED
        require(selectedIndex in question.options.indices) { "Invalid answer choice." }
        return if (selectedIndex == question.correctIndex) Outcome.CORRECT else Outcome.WRONG
    }
}
