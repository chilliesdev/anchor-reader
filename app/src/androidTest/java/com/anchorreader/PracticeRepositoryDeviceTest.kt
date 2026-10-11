package com.anchorreader

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchorreader.data.Book
import com.anchorreader.data.BookRepository
import com.anchorreader.data.Outcome
import com.anchorreader.data.PracticeRepository
import com.anchorreader.data.Progress
import com.anchorreader.data.QuestionSource
import com.anchorreader.data.QuestionTaskStatus
import com.anchorreader.data.QuestionValidator
import com.anchorreader.data.RecallQuestion
import com.anchorreader.data.SectionQuestionStatus
import com.anchorreader.data.normalizedSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual PDF extraction and atomic practice storage in a task-owned folder. No provider call. */
@RunWith(AndroidJUnit4::class)
class PracticeRepositoryDeviceTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var books: BookRepository
    private lateinit var practice: PracticeRepository
    private lateinit var book: Book

    @Before
    fun prepare() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.cacheDir, "practice-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        books = BookRepository(context)
        practice = PracticeRepository(context, books)
        val input = File(root, "river-guide.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("river-guide-numbered.pdf").use { source ->
            input.outputStream().use { source.copyTo(it) }
        }
        book = books.importPdf(Uri.fromFile(input), "Practice river guide", "QA")
        assertEquals("The fixture must contain two source sections.", 2, book.units.size)
        assertTrue(book.units.all { it.sourceSpans.isNotEmpty() })
    }

    @After
    fun clean() { root.deleteRecursively() }

    @Test
    fun sectionIdsQuestionsAndAllAnswerOutcomesSurviveReopen() {
        val reading = Progress(0, 1, setOf(0))
        books.saveProgress(book.id, reading)
        val task = requireNotNull(practice.start(book, "fixture-account").task)
        val questions = questions(0, 3)
        practice.sectionReady(book, task.id, task.accountId, 0, questions)
        listOf(Outcome.CORRECT, Outcome.WRONG, Outcome.SKIPPED).forEachIndexed { index, outcome ->
            practice.answer(book, questions[index], outcome)
        }

        val reopenedBooks = BookRepository(context)
        val reopenedBook = reopenedBooks.loadBooks().first { it.id == book.id }
        val reopened = PracticeRepository(context, reopenedBooks).load(reopenedBook)
        assertEquals(book.documentVersion, reopenedBook.documentVersion)
        assertEquals(book.units.map { it.id }, reopenedBook.units.map { it.id })
        assertEquals(questions.map { it.id }.toSet(), reopened.questions.map { it.id }.toSet())
        assertEquals(questions.map { it.version }.toSet(), reopened.questions.map { it.version }.toSet())
        assertEquals(listOf(Outcome.CORRECT, Outcome.WRONG, Outcome.SKIPPED), questions.map(reopened::answer))
        assertEquals(reading, reopenedBooks.loadProgress(book.id))
        assertTrue(File(requireNotNull(reopenedBook.pdfPath)).isFile)
        assertTrue(reopened.questions.all { q -> reopenedBook.units[q.unitIndex].sourceSpans.any {
            it.page == q.sourcePage && normalizedSource(it.text).contains(normalizedSource(q.sourceExcerpt))
        } })
    }

    @Test
    fun duplicateStartAndPartialRetryKeepCompletedSectionsAndAnswers() {
        val task = requireNotNull(practice.start(book, "fixture-account").task)
        assertEquals(task.id, practice.start(book, "fixture-account").task?.id)
        val first = questions(0).single()
        practice.sectionReady(book, task.id, task.accountId, 0, listOf(first))
        practice.answer(book, first, Outcome.CORRECT)
        practice.changeTask(book, task.id) { it.copy(status = QuestionTaskStatus.ERROR,
            sections = it.sections + (book.units[1].id to SectionQuestionStatus.FAILED)) }

        val retry = practice.start(book, task.accountId)
        val retryTask = requireNotNull(retry.task)
        assertNotEquals(task.id, retryTask.id)
        assertEquals(SectionQuestionStatus.READY, retryTask.sections[book.units[0].id])
        assertEquals(SectionQuestionStatus.PENDING, retryTask.sections[book.units[1].id])
        assertEquals(listOf(first), retry.questions)
        assertEquals(Outcome.CORRECT, retry.answer(first))
        assertNull(practice.sectionReady(book, task.id, task.accountId, 1, questions(1)))
        assertNull(practice.sectionReady(book, retryTask.id, "other-account", 1, questions(1)))

        val finished = requireNotNull(practice.sectionReady(book, retryTask.id, retryTask.accountId, 1, questions(1)))
        assertEquals(2, finished.readySections)
        assertEquals(2, finished.questions.map { it.id }.distinct().size)
        assertEquals(Outcome.CORRECT, finished.answer(first))
    }

    @Test
    fun processRecoveryPausesWorkAndRetainsCompletedSections() {
        val task = requireNotNull(practice.start(book, "fixture-account").task)
        val first = questions(0).single()
        practice.sectionReady(book, task.id, task.accountId, 0, listOf(first))
        practice.answer(book, first, Outcome.WRONG)

        val reopened = PracticeRepository(context, BookRepository(context))
        val recovered = reopened.load(book, recoverInterrupted = true)
        assertEquals(QuestionTaskStatus.PAUSED, recovered.task?.status)
        assertFalse(recovered.working)
        assertEquals(listOf(first), recovered.questions)
        assertEquals(Outcome.WRONG, recovered.answer(first))
        assertEquals(QuestionTaskStatus.PAUSED, PracticeRepository(context, books).load(book).task?.status)
        val resumed = reopened.start(book, task.accountId)
        assertNotEquals(task.id, resumed.task?.id)
        assertEquals(SectionQuestionStatus.READY, resumed.task?.sections?.get(book.units[0].id))
        assertEquals(SectionQuestionStatus.PENDING, resumed.task?.sections?.get(book.units[1].id))
        assertEquals(Outcome.WRONG, resumed.answer(first))
    }

    @Test
    fun cancelFencesLateResultsAndRemainsCanceledAfterReload() {
        val task = requireNotNull(practice.start(book, "fixture-account").task)
        practice.fenceTask(task.id)
        assertNull(practice.sectionReady(book, task.id, task.accountId, 0, questions(0)))
        practice.changeTask(book, task.id) { it.copy(status = QuestionTaskStatus.CANCELED) }

        val reopened = PracticeRepository(context, books)
        assertEquals(QuestionTaskStatus.CANCELED, reopened.load(book).task?.status)
        assertNull(reopened.sectionReady(book, task.id, task.accountId, 0, questions(0)))
        assertTrue(reopened.load(book).questions.isEmpty())
        assertTrue(File(requireNotNull(book.pdfPath)).isFile)
        assertNotNull(books.findBook(book.id))
    }

    @Test
    fun deletingBookRejectsLatePublicationAndCannotRestoreFiles() {
        val task = requireNotNull(practice.start(book, "fixture-account").task)
        val lateQuestions = questions(0)
        books.delete(book)
        practice.delete(book.id)

        assertTrue(runCatching { practice.sectionReady(book, task.id, task.accountId, 0, lateQuestions) }.isFailure)
        assertNull(practice.changeTask(book, task.id) { it.copy(status = QuestionTaskStatus.COMPLETE) })
        assertNull(books.findBook(book.id))
        assertFalse(File(requireNotNull(book.pdfPath)).exists())
        assertFalse(File(context.filesDir, "practice/${book.id}.json").exists())
        assertFalse(File(context.filesDir, "practice/${book.id}.json.bak").exists())
        assertEquals(0L, practice.storedBytes())
    }

    @Test
    fun replacementRetiresChangedAnswerAndKeepsOtherSectionAnswer() {
        val task = requireNotNull(practice.start(book, "fixture-account").task)
        val first = questions(0).single()
        val second = questions(1).single()
        practice.sectionReady(book, task.id, task.accountId, 0, listOf(first))
        practice.sectionReady(book, task.id, task.accountId, 1, listOf(second))
        practice.answer(book, first, Outcome.CORRECT)
        practice.answer(book, second, Outcome.SKIPPED)
        val replacement = questions(0, explanation = "Revised explanation supported by the same saved source.").single()
        assertEquals(first.id, replacement.id)
        assertNotEquals(first.version, replacement.version)

        val replaced = requireNotNull(practice.sectionReady(book, task.id, task.accountId, 0, listOf(replacement)))
        assertNull(replaced.answer(first))
        assertNull(replaced.answer(replacement))
        assertEquals(Outcome.SKIPPED, replaced.answer(second))
        practice.answer(book, replacement, Outcome.WRONG)
        val reopened = PracticeRepository(context, books).load(book)
        assertEquals(Outcome.WRONG, reopened.answer(replacement))
        assertEquals(Outcome.SKIPPED, reopened.answer(second))
        assertNull(reopened.answer(first))
    }

    @Test
    fun invalidReplacementDoesNotRemoveSavedQuestionsOrAnswers() {
        val task = requireNotNull(practice.start(book, "fixture-account").task)
        val question = questions(0).single()
        practice.sectionReady(book, task.id, task.accountId, 0, listOf(question))
        val before = practice.answer(book, question, Outcome.CORRECT)
        val invalid = question.copy(correctIndex = 8)

        assertTrue(runCatching { practice.sectionReady(book, task.id, task.accountId, 0, listOf(invalid)) }.isFailure)
        assertEquals(before, PracticeRepository(context, books).load(book))
    }

    @Test
    fun resetAnswersKeepsQuestionsSourceAndReadingProgress() {
        val reading = Progress(1, 2, setOf(0, 1))
        books.saveProgress(book.id, reading)
        val task = requireNotNull(practice.start(book, "fixture-account").task)
        val question = questions(0).single()
        practice.sectionReady(book, task.id, task.accountId, 0, listOf(question))
        practice.answer(book, question, Outcome.CORRECT)

        val reset = practice.resetAnswers(book)
        assertTrue(reset.answers.isEmpty())
        assertEquals(listOf(question), reset.questions)
        assertEquals(reading, books.loadProgress(book.id))
        assertTrue(File(requireNotNull(book.pdfPath)).isFile)
    }

    private fun questions(unit: Int, count: Int = 1, explanation: String = "The answer is stated by the saved source."): List<RecallQuestion> {
        val span = book.units[unit].sourceSpans.first()
        val excerpt = normalizedSource(span.text).take(160)
        val items = JSONArray()
        repeat(count) { index ->
            items.put(JSONObject().put("prompt", "Which statement appears in section ${unit + 1}, check ${index + 1}?")
                .put("options", JSONArray(listOf(excerpt, "The source requires an online account.", "The source recommends ignoring all safety checks.", "The source says saved reading is impossible.")))
                .put("correctIndex", 0).put("explanation", explanation).put("sourcePage", span.page).put("sourceExcerpt", excerpt))
        }
        val payload = JSONObject().put("sectionId", book.units[unit].id).put("questions", items)
        return QuestionValidator.parse(payload.toString(), book, unit, QuestionSource(book.units[unit].id, book.units[unit].sourceSpans, 3))
    }
}
