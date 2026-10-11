package com.anchorreader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchorreader.chatgpt.ChatGptProvider
import com.anchorreader.data.Book
import com.anchorreader.data.BookRepository
import com.anchorreader.data.Outcome
import com.anchorreader.data.PracticeRepository
import com.anchorreader.data.QuestionSource
import com.anchorreader.data.QuestionTaskStatus
import com.anchorreader.data.QuestionValidator
import com.anchorreader.data.RecallQuestion
import com.anchorreader.data.normalizedSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/** Real PDF and disk writes. Only answer scheduling is controlled; no provider request. */
@RunWith(AndroidJUnit4::class)
class PracticeWriteDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var books: BookRepository
    private lateinit var practice: PracticeRepository
    private lateinit var book: Book
    private lateinit var question: RecallQuestion
    private lateinit var model: ReaderViewModel
    private val store = ViewModelStore()
    private val dispatcher = QueuedAnswerDispatcher()

    @Before
    fun prepare() {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.getSharedPreferences("preferences", 0).edit().clear().commit()
        books = BookRepository(context)
        practice = PracticeRepository(context, books)
        val input = sourceFile()
        book = books.importPdf(Uri.fromFile(input), "Saved practice writes", "QA")
        input.delete()
        val span = book.units.first().sourceSpans.first()
        val excerpt = normalizedSource(span.text).take(160)
        val item = JSONObject().put("prompt", "Which statement is in the saved source?")
            .put("options", JSONArray(listOf(excerpt, "No reading can be saved.", "A password is a river guide.", "All safety checks must be ignored.")))
            .put("correctIndex", 0).put("explanation", "The statement is in the saved source.")
            .put("sourcePage", span.page).put("sourceExcerpt", excerpt)
        question = QuestionValidator.parse(JSONObject().put("sectionId", book.units.first().id)
            .put("questions", JSONArray().put(item)).toString(), book, 0,
            QuestionSource(book.units.first().id, book.units.first().sourceSpans, 3)).single()
        val task = requireNotNull(practice.start(book, "fixture-account").task)
        practice.sectionReady(book, task.id, task.accountId, 0, listOf(question))
        practice.changeTask(book, task.id) { it.copy(status = QuestionTaskStatus.COMPLETE) }
        main {
            val application = context.applicationContext as Application
            model = ReaderViewModel(application, ChatGptProvider(application), dispatcher)
            store.put("practice-write", model)
        }
        await("initial_load") { !model.state.value.loading }
    }

    @After
    fun close() { dispatcher.release(); main { store.clear() } }

    @Test
    fun queuedAnswerCannotReturnAfterResetAndLaterDeliberateAnswerStillSaves() {
        answer(question.correctIndex)
        await("first_answer_saved") { model.state.value.practice[book.id]?.answer(question) == Outcome.CORRECT }
        assertEquals(Outcome.CORRECT, reopened().answer(question))

        dispatcher.hold = true
        main { model.retryQuestion(); model.selectAnswer(1); model.answer() }
        assertEquals(1, dispatcher.queuedCount)
        val oldAnswer = requireNotNull(dispatcher.heldJob)
        assertFalse(oldAnswer.isCompleted)
        main { model.openSettings(); model.resetProgress() }
        await("queued_answer_reset") { !model.state.value.loading && model.state.value.practice[book.id]?.answers?.isEmpty() == true }
        assertTrue(reopened().answers.isEmpty())
        assertFalse(oldAnswer.isCompleted)

        dispatcher.release()
        runBlocking { withTimeout(10_000) { oldAnswer.join() } }
        main { assertTrue(model.state.value.practice[book.id]?.answers?.isEmpty() == true) }
        assertTrue(reopened().answers.isEmpty())
        assertEquals(listOf(question), reopened().questions)

        answer(1)
        await("later_answer_saved") { model.state.value.practice[book.id]?.answer(question) == Outcome.WRONG }
        assertEquals(Outcome.WRONG, reopened().answer(question))
        assertEquals(question.version, reopened().questions.single().version)
        assertEquals(0, dispatcher.queuedCount)
    }

    @Test
    fun storageTotalIncludesPracticeAfterProgressImportDeleteAndReset() {
        assertTotal()
        answer(question.correctIndex)
        await("storage_answer_saved") { model.state.value.practice[book.id]?.answer(question) == Outcome.CORRECT }
        awaitTotal("answer_byte_total")
        main { model.start(book); model.setSentence(1) }
        val progressFile = File(context.filesDir, "progress/${book.id}.json")
        // AtomicFile.openRead can remove a writer's .new. Observe the committed base
        // without invoking another repository's recovery while the model is writing.
        await("progress_committed") {
            runCatching { JSONObject(progressFile.readText()).getInt("sentenceIndex") == 1 }.getOrDefault(false)
        }
        assertEquals(1, books.loadProgress(book.id).sentenceIndex)
        awaitTotal("progress_byte_total")

        val input = sourceFile()
        main { model.importPdf(Uri.fromFile(input), "Second saved PDF", "QA") }
        await("second_import_saved") { !model.state.value.loading && model.state.value.books.any { it.title == "Second saved PDF" } }
        input.delete()
        assertTotal()
        val addedId = model.state.value.activeId
        main { model.deleteBook() }
        await("second_book_deleted") { !model.state.value.loading && model.state.value.books.none { it.id == addedId } }
        assertTotal()

        main { model.openSettings(); model.resetProgress() }
        await("storage_answers_reset") { !model.state.value.loading && model.state.value.practice[book.id]?.answers?.isEmpty() == true }
        assertTrue(reopened().answers.isEmpty())
        assertTotal()
    }

    private fun answer(index: Int) = main {
        model.details(book)
        model.openPractice()
        model.selectAnswer(index)
        model.answer()
    }

    private fun sourceFile(): File = File(context.cacheDir, "practice-write-source.pdf").also { file ->
        instrumentation.context.assets.open("river-guide-numbered.pdf").use { source ->
            file.outputStream().use { source.copyTo(it) }
        }
    }

    private fun reopened() = PracticeRepository(context, BookRepository(context)).load(book)
    private fun total() = books.storedBytes() + practice.storedBytes()
    private fun assertTotal() {
        assertTrue(practice.storedBytes() > 0)
        main { assertEquals(total(), model.state.value.storedBytes) }
    }
    private fun awaitTotal(step: String) { await(step) { model.state.value.storedBytes == total() }; assertTotal() }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(step: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            Thread.sleep(10)
        }
        main {
            val state = model.state.value
            assertTrue("Saved state timeout: step=$step loading=${state.loading} error=${state.error != null} " +
                "books=${state.books.size.coerceAtMost(1000)} bytes_match=${state.storedBytes == total()}", condition())
        }
    }

    private class QueuedAnswerDispatcher : CoroutineDispatcher() {
        @Volatile var hold = false
        @Volatile var heldJob: Job? = null
        private val queued = ConcurrentLinkedQueue<Pair<CoroutineContext, Runnable>>()
        val queuedCount get() = queued.size
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (hold) {
                heldJob = context[Job]
                queued.add(context to block)
            } else Dispatchers.Main.dispatch(context, block)
        }
        fun release() {
            hold = false
            while (true) {
                val (context, block) = queued.poll() ?: break
                Dispatchers.Main.dispatch(context, block)
            }
        }
    }
}
