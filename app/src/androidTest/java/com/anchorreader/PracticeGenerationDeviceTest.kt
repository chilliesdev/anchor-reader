package com.anchorreader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchorreader.chatgpt.*
import com.anchorreader.data.*
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Runs the actual generation/save path with a real PDF and synthetic in-memory provider replies. */
@RunWith(AndroidJUnit4::class)
class PracticeGenerationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val application get() = context.applicationContext as Application
    private lateinit var books: BookRepository
    private lateinit var book: Book
    private lateinit var model: ReaderViewModel
    private val models = ViewModelStore()
    private val reading = Progress(0, 1, setOf(0))

    @Before fun prepare() {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.getSharedPreferences("preferences", 0).edit().clear().commit()
        books = BookRepository(context)
        val file = File(context.cacheDir, "generation-source.pdf")
        instrumentation.context.assets.open("river-guide-numbered.pdf").use { source -> file.outputStream().use { source.copyTo(it) } }
        book = books.importPdf(Uri.fromFile(file), "Generated River practice", "QA")
        file.delete()
        assertEquals(2, book.units.size)
        assertTrue(book.units.all { QuestionSources.chunks(it).size == 1 })
        books.saveProgress(book.id, reading)
    }

    @After fun close() { main { models.clear() } }

    @Test fun recoveredFinalizedQuestionsPassTheRealValidatorSaveReloadAndOfflineAnswerPath() {
        val fixture = Fixture(listOf(Reply.RECOVER, Reply.RECOVER))
        generate(fixture, QuestionTaskStatus.COMPLETE)
        assertEquals(2, fixture.posts.get())
        assertEquals(2, fixture.completions.size)
        assertTrue(fixture.records.isEmpty())
        val saved = reopened()
        assertEquals(2, saved.questions.size)
        assertTrue(saved.task!!.sections.values.all { it == SectionQuestionStatus.READY })
        saved.questions.forEach { question -> assertTrue(book.units[question.unitIndex].sourceSpans.any {
            it.page == question.sourcePage && normalizedSource(it.text).contains(question.sourceExcerpt)
        }) }
        val offline = Fixture(emptyList(), connected = false)
        launch(offline)
        main { model.details(book); model.openPractice(); model.selectAnswer(0); model.answer() }
        await { model.state.value.currentPractice.answer(saved.questions.first()) == Outcome.CORRECT }
        assertEquals(Outcome.CORRECT, reopened().answer(saved.questions.first()))
        assertEquals(0, offline.posts.get())
        assertEquals(0, offline.catalogCalls.get())
        assertReadable()
    }

    @Test fun recoveredMalformedJsonAndInvalidSectionPageExcerptChoicesOrCountNeverSaveQuestions() {
        for (reply in listOf(Reply.RECOVER_INVALID_JSON, Reply.RECOVER_BAD_SECTION, Reply.RECOVER_BAD_PAGE,
            Reply.RECOVER_BAD_EVIDENCE, Reply.RECOVER_BAD_INDEX, Reply.RECOVER_BAD_CHOICES, Reply.RECOVER_TOO_MANY)) {
            val fixture = Fixture(listOf(reply))
            generate(fixture, QuestionTaskStatus.ERROR)
            assertEquals(1, fixture.posts.get())
            assertTrue(reopened().questions.isEmpty())
            if (reply == Reply.RECOVER_INVALID_JSON) {
                assertEquals(ProviderStreamReason.RESULT_JSON, fixture.records.single().streamReason)
                assertTrue(fixture.completions.isEmpty())
            } else {
                assertTrue(fixture.records.isEmpty())
                assertEquals(1, fixture.completions.size) // Parser branch succeeded; source/content validation failed.
            }
            assertReadable()
        }
    }

    @Test fun twoSectionsGenerateSaveReloadAndAnswerOfflineWithoutAnyProviderRequest() {
        val fixture = Fixture(listOf(Reply.COMPLETE, Reply.COMPLETE))
        generate(fixture, QuestionTaskStatus.COMPLETE)
        assertEquals(2, fixture.posts.get())
        assertEquals(2, fixture.catalogCalls.get())
        assertTrue(fixture.records.isEmpty())
        val saved = reopened()
        assertEquals(2, saved.questions.size)
        assertTrue(saved.task!!.sections.values.all { it == SectionQuestionStatus.READY })
        saved.questions.forEach { question ->
            val spans = book.units[question.unitIndex].sourceSpans
            assertTrue(spans.any { it.page == question.sourcePage && normalizedSource(it.text).contains(question.sourceExcerpt) })
        }
        val offline = Fixture(emptyList(), connected = false)
        launch(offline)
        assertFalse(model.state.value.account.connected)
        assertEquals(saved.questions, model.state.value.practice[book.id]!!.questions)
        assertEquals(reading, model.state.value.progress[book.id])
        main { model.details(book); model.openPractice(); model.selectAnswer(0); model.answer() }
        await { model.state.value.currentPractice.answer(saved.questions.first()) == Outcome.CORRECT }
        assertEquals(Outcome.CORRECT, reopened().answer(saved.questions.first()))
        main { model.start(book); model.cancelPlaybackRequest() }
        assertEquals(Screen.PLAYER, model.state.value.screen)
        assertEquals(reading, model.state.value.currentProgress)
        assertTrue(File(book.pdfPath!!).isFile)
        assertEquals(0, offline.posts.get())
        assertEquals(0, offline.catalogCalls.get())
        assertNull(model.state.value.browserUrl)
    }

    @Test fun explicitEmptyFirstCompletionCreatesErrorSavesNoQuestionsAndKeepsBookReadable() {
        val fixture = Fixture(listOf(Reply.EMPTY))
        generate(fixture, QuestionTaskStatus.ERROR)
        assertEquals(1, fixture.posts.get())
        val saved = reopened()
        assertTrue(saved.questions.isEmpty())
        assertTrue(saved.task!!.sections.values.all { it == SectionQuestionStatus.PENDING })
        assertEquals(ProviderStreamReason.OUTPUT_EMPTY, fixture.records.single().streamReason)
        assertTrue(fixture.records.single().evidenceLine()!!.contains("terminal_output=empty"))
        assertReadable()
    }

    @Test fun secondSectionFailureRetainsFirstReadySectionThroughReloadAndOfflinePractice() {
        val fixture = Fixture(listOf(Reply.COMPLETE, Reply.EMPTY))
        generate(fixture, QuestionTaskStatus.ERROR)
        assertEquals(2, fixture.posts.get())
        val saved = reopened()
        assertEquals(1, saved.questions.size)
        assertEquals(SectionQuestionStatus.READY, saved.task!!.sections[book.units[0].id])
        assertEquals(SectionQuestionStatus.PENDING, saved.task.sections[book.units[1].id])
        val offline = Fixture(emptyList(), connected = false)
        launch(offline)
        main { model.details(book); model.openPractice() }
        assertEquals(Screen.CHECK, model.state.value.screen)
        assertEquals(saved.questions, model.state.value.questions)
        assertEquals(QuestionTaskStatus.ERROR, model.state.value.currentPractice.task!!.status)
        assertEquals(0, offline.posts.get())
        assertEquals(0, offline.catalogCalls.get())
        assertReadable()
    }

    @Test fun validEmptyQuestionsAreInsufficientSectionsWithACompleteTask() {
        val fixture = Fixture(listOf(Reply.INSUFFICIENT, Reply.INSUFFICIENT))
        generate(fixture, QuestionTaskStatus.COMPLETE)
        assertEquals(2, fixture.posts.get())
        assertTrue(reopened().questions.isEmpty())
        assertTrue(reopened().task!!.sections.values.all { it == SectionQuestionStatus.INSUFFICIENT })
        assertTrue(fixture.records.isEmpty())
        assertReadable()
    }

    @Test fun malformedQuestionJsonCannotSaveQuestionsOrAdvanceToTheSecondSection() {
        val fixture = Fixture(listOf(Reply.INVALID_JSON))
        generate(fixture, QuestionTaskStatus.ERROR)
        assertEquals(1, fixture.posts.get())
        assertTrue(reopened().questions.isEmpty())
        assertEquals(ProviderStreamReason.RESULT_JSON, fixture.records.single().streamReason)
        assertReadable()
    }

    @Test fun fabricatedSourcePageExcerptAndAnswerIndexAreRejectedBeforePersistence() {
        for (reply in listOf(Reply.BAD_PAGE, Reply.BAD_EVIDENCE, Reply.BAD_INDEX)) {
            val fixture = Fixture(listOf(reply))
            generate(fixture, QuestionTaskStatus.ERROR)
            assertEquals(1, fixture.posts.get())
            assertTrue(reopened().questions.isEmpty())
            assertTrue(fixture.records.isEmpty()) // Provider completed; local question validation rejects it.
            assertReadable()
        }
    }

    @Test fun httpAndNetworkFailureStopOnceWithoutLosingThePdfOrReadingPosition() {
        for (reply in listOf(Reply.HTTP_FAILURE, Reply.NETWORK_FAILURE)) {
            val fixture = Fixture(listOf(reply))
            generate(fixture, QuestionTaskStatus.ERROR)
            assertEquals(1, fixture.posts.get())
            assertTrue(reopened().questions.isEmpty())
            val record = fixture.records.single()
            if (reply == Reply.HTTP_FAILURE) {
                assertEquals(ProviderFailureKind.NETWORK, record.failureKind)
                assertEquals(503, record.httpStatus)
                assertEquals("ChatGPT is unavailable. Try again later.", reopened().task!!.message)
            } else {
                assertEquals(ProviderExceptionLabel.IO, record.exceptionLabel)
                assertEquals("ChatGPT request stopped. Try again.", reopened().task!!.message)
            }
            assertFalse(reopened().task!!.message.contains("FAKE_SECRET"))
            assertReadable()
        }
    }

    @Test fun capOneSavesFirstSectionThenStopsBeforeASecondPostAndStaysSpentOnManualRetry() {
        val fixture = Fixture(listOf(Reply.RECOVER), cap = 1)
        generate(fixture, QuestionTaskStatus.ERROR)
        assertEquals(1, fixture.posts.get())
        assertEquals(1, reopened().questions.size)
        assertEquals(1, fixture.completions.size)
        assertEquals(SectionQuestionStatus.READY, reopened().task!!.sections[book.units[0].id])
        main { model.addQuestions() }
        await { model.state.value.currentPractice.task?.id != fixtureTaskId && model.state.value.currentPractice.task?.status == QuestionTaskStatus.ERROR }
        assertEquals(1, fixture.posts.get())
        assertEquals(1, reopened().questions.size)
        assertEquals(1, fixture.completions.size)
        assertEquals("Generation stopped. Your book and ready questions are saved.", reopened().task!!.message)
        assertReadable()
    }

    private var fixtureTaskId: String? = null
    private fun generate(fixture: Fixture, status: QuestionTaskStatus) {
        launch(fixture)
        val previousTaskId = model.state.value.practice[book.id]?.task?.id
        main { model.details(book); model.addQuestions() }
        await { model.state.value.currentPractice.task?.let { it.id != previousTaskId && it.status == status } == true }
        fixtureTaskId = model.state.value.currentPractice.task!!.id
        assertEquals(status, reopened().task!!.status)
        assertEquals(reading, books.loadProgress(book.id))
    }

    private fun launch(fixture: Fixture) {
        main {
            models.clear()
            val provider = fixture.provider()
            if (fixture.connected) context.getSharedPreferences("preferences", 0).edit()
                .putBoolean("plan-confirmed-${fixture.accountId}", true).commit()
            model = ReaderViewModel(application, provider)
            models.put("generation", model)
        }
        await { !model.state.value.loading && (!fixture.connected || model.state.value.account.connected) }
    }

    private fun assertReadable() {
        assertNotNull(books.findBook(book.id))
        assertTrue(File(book.pdfPath!!).isFile)
        main { model.start(book); model.cancelPlaybackRequest() }
        assertEquals(Screen.PLAYER, model.state.value.screen)
        assertEquals(reading, model.state.value.currentProgress)
        assertNull(model.state.value.browserUrl)
    }
    private fun reopened() = PracticeRepository(context, BookRepository(context)).load(book)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            Thread.sleep(10)
        }
        main { assertTrue("Timed out waiting for generation state.", condition()) }
    }

    private enum class Reply { COMPLETE, EMPTY, INSUFFICIENT, INVALID_JSON, BAD_PAGE, BAD_EVIDENCE, BAD_INDEX, HTTP_FAILURE, NETWORK_FAILURE,
        RECOVER, RECOVER_INVALID_JSON, RECOVER_BAD_SECTION, RECOVER_BAD_PAGE, RECOVER_BAD_EVIDENCE, RECOVER_BAD_INDEX, RECOVER_BAD_CHOICES, RECOVER_TOO_MANY }
    private class Fixture(private val replies: List<Reply>, val connected: Boolean = true, private val cap: Int = 0) {
        val posts = AtomicInteger()
        val catalogCalls = AtomicInteger()
        val records = CopyOnWriteArrayList<ProviderFailureRecord>()
        val completions = CopyOnWriteArrayList<ProviderCompletionRecord>()
        private val now = 1_800_000_000_000L
        private val registration = Registration("fixture-client", "fixture-subject", "https://fixture.invalid", "fixture@example.invalid",
            "FAKE_ID_TOKEN", "FAKE_ACCESS_TOKEN", "FAKE_REFRESH_TOKEN", PLAN_SCOPES.split(' ').toSet(), now + 3_600_000)
        val accountId = registration.accountId!!
        private val store = object : CredentialStore {
            override fun load() = if (connected) ConnectionData("fixture-host", registration.clientId, listOf(registration)) else ConnectionData("fixture-host")
            override fun save(data: ConnectionData) { throw AssertionError("Synthetic current credentials must not refresh") }
        }
        private val http = object : ChatGptHttp {
            override fun open(url: String, method: String, headers: Map<String, String>, body: ByteArray?): HttpReply {
                assertTrue("Offline practice must not call the provider", connected)
                assertEquals("Bearer FAKE_ACCESS_TOKEN", headers["Authorization"])
                return when (url) {
                    "https://fixture.invalid/v1/models" -> {
                        catalogCalls.incrementAndGet()
                        assertEquals("GET", method)
                        reply(200, "{\"models\":[{\"visibility\":\"list\",\"slug\":\"fixture-model\"}]}")
                    }
                    "https://fixture.invalid/v1/responses" -> {
                        val index = posts.getAndIncrement()
                        assertEquals("POST", method)
                        assertTrue("Unexpected extra inference request", index in replies.indices)
                        val request = JSONObject(body!!.toString(Charsets.UTF_8))
                        assertEquals("fixture-model", request.getString("model"))
                        assertTrue(request.getBoolean("stream"))
                        assertFalse(request.getBoolean("store"))
                        assertTrue(request.getJSONObject("text").getJSONObject("format").getBoolean("strict"))
                        val mode = replies[index]
                        val recover = mode.name.startsWith("RECOVER")
                        if (mode == Reply.NETWORK_FAILURE) throw IOException("FAKE_SECRET_IO")
                        if (mode == Reply.HTTP_FAILURE) return reply(503, "{\"error\":{\"code\":\"unavailable\"}}")
                        val source = JSONObject(request.getJSONArray("input").getJSONObject(0).getString("content"))
                        val page = source.getJSONArray("pages").getJSONObject(0)
                        val excerpt = normalizedSource(page.getString("text")).take(160)
                        val question = JSONObject().put("prompt", "Which statement occurs in this section?")
                            .put("options", JSONArray(if (mode == Reply.RECOVER_BAD_CHOICES) listOf(excerpt, excerpt, excerpt, excerpt) else
                                listOf(excerpt, "A password describes river safety.", "Books cannot be stored.", "All safety checks must be ignored.")))
                            .put("correctIndex", if (mode in setOf(Reply.BAD_INDEX, Reply.RECOVER_BAD_INDEX)) 4 else 0).put("explanation", "The source contains this statement.")
                            .put("sourcePage", if (mode in setOf(Reply.BAD_PAGE, Reply.RECOVER_BAD_PAGE)) 999 else page.getInt("page"))
                            .put("sourceExcerpt", if (mode in setOf(Reply.BAD_EVIDENCE, Reply.RECOVER_BAD_EVIDENCE)) "This fabricated phrase never appears in this PDF." else excerpt)
                        val questions = if (mode == Reply.INSUFFICIENT) JSONArray() else JSONArray().put(question)
                        if (mode == Reply.RECOVER_TOO_MANY) repeat(3) { questions.put(question) }
                        val result = if (mode in setOf(Reply.INVALID_JSON, Reply.RECOVER_INVALID_JSON)) "{invalid" else JSONObject()
                            .put("sectionId", if (mode == Reply.RECOVER_BAD_SECTION) "wrong-section" else source.getString("sectionId"))
                            .put("questions", questions).toString()
                        val output = if (mode == Reply.EMPTY) JSONArray() else JSONArray().put(JSONObject().put("type", "message")
                            .put("role", "assistant").put("status", "completed").put("content", JSONArray().put(JSONObject()
                                .put("type", "output_text").put("text", result))))
                        val finalized = if (recover) "event: response.output_item.done\ndata: ${JSONObject().put("type", "response.output_item.done")
                            .put("output_index", 0).put("item", output.getJSONObject(0))}\n\n" else ""
                        val terminal = JSONObject().put("type", "response.completed").put("response", JSONObject().put("status", "completed")
                            .put("output", if (recover) JSONArray() else output))
                        reply(200, finalized + "event: response.completed\ndata: $terminal\n\n")
                    }
                    else -> throw AssertionError("No real endpoint or sign-in is allowed")
                }
            }
            override fun cancelAll() {}
        }
        private fun reply(status: Int, body: String) = HttpReply(status, emptyMap(), body.byteInputStream()) {}
        fun provider() = ChatGptProvider(store, http, ProviderEndpoints(resource = "https://fixture.invalid/v1"), ProviderClock { now },
            Dispatchers.Main.immediate, ProviderFailureSink { records += it }, cap, ProviderCompletionSink { completions += it })
    }
}
