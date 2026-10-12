package com.anchorreader

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
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
import com.anchorreader.data.normalizedSource
import com.anchorreader.ui.AnchorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Saved practice uses a real source PDF and a controlled device voice. No live sign-in or provider call. */
@RunWith(AndroidJUnit4::class)
class PracticeJourneyDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: ReaderViewModel
    private lateinit var book: Book
    private lateinit var books: BookRepository
    private val voice = ControlledNarration()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val reading = Progress(0, 1, setOf(0))

    @Before
    fun launch() {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.getSharedPreferences("preferences", 0).edit().clear().commit()
        books = BookRepository(context)
        val input = File(context.cacheDir, "practice-journey-source.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("river-guide-numbered.pdf").use { source ->
            input.outputStream().use { source.copyTo(it) }
        }
        book = books.importPdf(Uri.fromFile(input), "River practice journey", "QA")
        input.delete()
        assertEquals(2, book.units.size)
        assertTrue(book.units.first().sentences.size > reading.sentenceIndex)
        books.saveProgress(book.id, reading)
        val practice = PracticeRepository(context, books)
        val task = requireNotNull(practice.start(book, "fixture-account").task)
        practice.sectionReady(book, task.id, task.accountId, 0, fixtureQuestions())
        practice.changeTask(book, task.id) { it.copy(status = QuestionTaskStatus.COMPLETE) }
        launchContent()
    }

    @After
    fun close() { scenario.close() }

    @Test
    fun multipleQuestionsSourceAndRetryReturnToExactSentencePaused() {
        compose.runOnIdle { voice.ready(); model.start(book) }
        compose.waitUntil(10_000) { voice.spoken.size == 1 }
        val spokenBefore = voice.spoken.size
        compose.onNodeWithContentDescription("Quick check").performClick()
        waitForScreen(Screen.CHECK)
        assertFalse(voice.state.value.playing)
        assertEquals(reading, model.state.value.currentProgress)
        val first = model.state.value.questions.first()
        answer(first.correctIndex)
        compose.onNodeWithText("View source · page ${first.sourcePage}").performScrollTo().performClick()
        waitForSourcePage(first.sourcePage)
        compose.onNodeWithContentDescription("Source PDF page ${first.sourcePage}").assertExists()
        compose.onNodeWithText("Back to lesson").performClick()
        click("Next question")

        val second = model.state.value.questions[1]
        answer((second.correctIndex + 1) % second.options.size)
        click("Try again")
        compose.onNodeWithText("Check answer").assertExists()
        answer(second.correctIndex)
        click("Next question")
        val third = model.state.value.questions[2]
        click("Skip")
        click("Continue reading")

        waitForScreen(Screen.PLAYER)
        compose.onNodeWithTag("current_sentence").assertTextEquals(book.units[0].sentences[1])
        compose.onNodeWithContentDescription("Play narration").assertIsEnabled()
        assertFalse(voice.state.value.playing)
        assertEquals(spokenBefore, voice.spoken.size)
        assertEquals(reading, model.state.value.currentProgress)
        compose.waitUntil(10_000) { model.state.value.currentPractice.answers.size == 3 }
        val reopened = PracticeRepository(context, BookRepository(context)).load(book)
        assertEquals(Outcome.CORRECT, reopened.answer(first))
        assertEquals(Outcome.CORRECT, reopened.answer(second))
        assertEquals(Outcome.SKIPPED, reopened.answer(third))
        assertEquals(reading, BookRepository(context).loadProgress(book.id))
    }

    @Test
    fun completeShowsOnlySessionAnswersAndPreservesReadingProgressAfterRestart() {
        DeviceEvidence.capture("imported-library-ready-questions")
        compose.runOnIdle { voice.ready(); model.start(book) }
        compose.waitUntil(10_000) { voice.spoken.isNotEmpty() }
        compose.onNodeWithContentDescription("Quick check").performClick()
        waitForScreen(Screen.CHECK)
        val questions = model.state.value.questions.toList()
        DeviceEvidence.capture("imported-concept-check-question")
        answer(questions[0].correctIndex)
        click("Next question")
        answer((questions[1].correctIndex + 1) % questions[1].options.size)
        click("Next question")
        click("Skip")
        click("Complete")
        waitForScreen(Screen.FINISH)
        assertEquals(questions, model.state.value.questions)
        assertEquals(reading, model.state.value.currentProgress)
        assertFalse(model.state.value.currentProgress.completed)
        assertFalse(voice.state.value.playing)
        compose.onNodeWithText("You completed this check.").assertExists()
        compose.onNodeWithText("1 / 2").assertExists()
        DeviceEvidence.capture("imported-concept-check-results")
        compose.onNodeWithText("Wrong · Review suggested").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Skipped").assertExists()
        compose.onNodeWithText("Answer record only. This is not a mastery score.").assertDoesNotExist()
        compose.waitUntil(10_000) { model.state.value.currentPractice.answers.size == 3 }
        scenario.close()
        launchContent()
        assertEquals(reading, model.state.value.progress[book.id])
        val saved = model.state.value.practice.getValue(book.id)
        assertEquals(Outcome.CORRECT, saved.answer(questions[0]))
        assertEquals(Outcome.WRONG, saved.answer(questions[1]))
        assertEquals(Outcome.SKIPPED, saved.answer(questions[2]))
    }

    @Test
    fun continueReadingAndSystemBackReturnToDetailsWithoutChangingPosition() {
        compose.runOnIdle { model.details(book) }
        openAllPractice()
        click("Continue reading")
        waitForScreen(Screen.OVERVIEW)
        assertEquals(reading, model.state.value.currentProgress)

        openAllPractice()
        Espresso.pressBack()
        waitForScreen(Screen.OVERVIEW)
        assertEquals(reading, model.state.value.currentProgress)
        assertFalse(model.state.value.autoplayRequested)
        assertFalse(voice.state.value.playing)
        assertEquals(0, voice.spoken.size)
        assertEquals(reading, books.loadProgress(book.id))
    }

    @Test
    fun storedAnswersAndSourceRemainUsableAfterActivityRestart() {
        compose.runOnIdle { model.details(book) }
        openAllPractice()
        val first = model.state.value.questions.first()
        answer(first.correctIndex)
        compose.waitUntil(10_000) { model.state.value.currentPractice.answer(first) == Outcome.CORRECT }
        scenario.close()
        launchContent()

        assertEquals(reading, model.state.value.progress[book.id])
        assertEquals(Outcome.CORRECT, model.state.value.practice[book.id]?.answer(first))
        compose.runOnIdle { model.details(book) }
        openAllPractice()
        click("Skip")
        val question = model.state.value.questions.first()
        click("View source · page ${question.sourcePage}")
        waitForSourcePage(question.sourcePage)
        compose.onNodeWithText("Back to lesson").performClick()
        click("Continue reading")
        waitForScreen(Screen.OVERVIEW)
        assertEquals(reading, books.loadProgress(book.id))
        assertNull(model.state.value.browserUrl)
        assertFalse(model.state.value.account.signingIn)
    }

    @Test
    fun narrowLargeTextOverviewActionsOpenOriginalPdfAndTranscript() {
        scenario.onActivity { activity ->
            activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                    AnchorTheme { Box(Modifier.width(320.dp).fillMaxHeight()) { ReaderApp(voice, model) } }
                }
            }
        }
        compose.runOnIdle { model.details(book) }
        click("Source")
        val sourcePage = book.units[reading.unitIndex].sourcePage ?: 1
        waitForSourcePage(sourcePage)
        compose.onNodeWithContentDescription("Source PDF page $sourcePage").assertExists()
        compose.onNodeWithText("Back to lesson").performClick()
        waitForScreen(Screen.OVERVIEW)
        click("Full text")
        compose.onNodeWithText("FULL TEXT").assertExists()
        compose.onNodeWithContentDescription("Close transcript").performClick()
        waitForScreen(Screen.OVERVIEW)
        assertEquals(reading, model.state.value.currentProgress)
        assertFalse(voice.state.value.playing)
        assertFalse(model.state.value.autoplayRequested)
    }

    @Test
    fun pdfQuestionOptionStartsOffAndExplainsTransferOnlyWhenEnabled() {
        compose.onNodeWithText("New").assertIsDisplayed().performClick()
        click("Import PDF")
        val option = isToggleable() and hasClickAction() and
            (hasText("Add practice questions") or hasAnyDescendant(hasText("Add practice questions")))
        compose.onNode(option).performScrollTo().assertIsOff()
        compose.onNodeWithText("Use my ChatGPT plan").assertDoesNotExist()
        compose.onNodeWithText("Save book").assertExists()

        compose.onNode(option).performClick()
        compose.onNode(option).assertIsOn()
        compose.onNodeWithText("Use my ChatGPT plan").assertExists()
        compose.onNodeWithText("Save and add questions").assertExists()
        compose.onNode(option).performScrollTo().performClick()
        compose.onNode(option).assertIsOff()
        compose.onNodeWithText("Save book").assertExists()
        assertNull(model.state.value.browserUrl)
        assertFalse(model.state.value.account.signingIn)
        assertEquals(QuestionTaskStatus.COMPLETE, model.state.value.practice[book.id]?.task?.status)
    }

    private fun launchContent() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            model = ViewModelProvider(it, ViewModelProvider.AndroidViewModelFactory.getInstance(it.application))[ReaderViewModel::class.java]
            it.setContent { AnchorTheme { ReaderApp(voice, model) } }
        }
        compose.waitUntil(10_000) { !model.state.value.loading }
    }

    private fun openAllPractice() {
        compose.onNode(hasText("Practice questions") and hasClickAction()).performScrollTo().performClick()
        waitForScreen(Screen.CHECK)
    }

    private fun answer(index: Int) {
        val question = model.state.value.questions[model.state.value.questionIndex]
        click(question.options[index])
        click("Check answer")
        compose.waitUntil(10_000) { model.state.value.outcome != null }
    }

    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }

    private fun waitForScreen(screen: Screen) {
        compose.waitUntil(10_000) { model.state.value.screen == screen }
        compose.waitForIdle()
    }

    private fun waitForSourcePage(page: Int?) {
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Source PDF page $page").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: ComposeTimeoutException) {
            val dialog = runCatching { compose.onAllNodesWithContentDescription("Close source").fetchSemanticsNodes().isNotEmpty() }.getOrNull()
            val image = runCatching { compose.onAllNodesWithContentDescription("Source PDF page $page").fetchSemanticsNodes().isNotEmpty() }.getOrNull()
            val renderError = runCatching {
                compose.onAllNodesWithText("Could not display this source page. Full text remains available.").fetchSemanticsNodes().isNotEmpty()
            }.getOrNull()
            val pdf = book.pdfPath?.let(::File)
            throw AssertionError("PDF source timeout: page=$page dialog=$dialog image=$image render_error=$renderError " +
                "pdf_exists=${pdf?.isFile == true} pdf_nonempty=${pdf?.length()?.let { it > 0 } == true} screen=${model.state.value.screen}", failure)
        }
    }

    private fun fixtureQuestions(): List<RecallQuestion> {
        val span = book.units[0].sourceSpans.first()
        val examples = listOf(
            listOf("What should you read before a river trip?", "The river guide", "A bank statement", "A weather advertisement", "A store receipt", "Read the river guide before a trip."),
            listOf("What should you check before launching?", "The water depth", "The phone wallpaper", "The library count", "The account avatar", "Check the water depth before you launch."),
            listOf("Who can help plan a route?", "A trained guide", "A browser window", "An account password", "A shopping receipt", "A trained guide can help you plan a route"),
        )
        val items = JSONArray()
        examples.forEach { example ->
            assertTrue(normalizedSource(span.text).contains(example.last()))
            items.put(JSONObject().put("prompt", example[0]).put("options", JSONArray(example.subList(1, 5)))
                .put("correctIndex", 0).put("explanation", "The saved source states: ${example.last()}")
                .put("sourcePage", span.page).put("sourceExcerpt", example.last()))
        }
        return QuestionValidator.parse(JSONObject().put("sectionId", book.units[0].id).put("questions", items).toString(),
            book, 0, QuestionSource(book.units[0].id, book.units[0].sourceSpans, 3))
    }

    private class ControlledNarration : Narration {
        val mutableState = MutableStateFlow(VoiceState())
        override val state = mutableState
        val spoken = mutableListOf<String>()
        fun ready() { mutableState.value = mutableState.value.copy(ready = true, initializing = false) }
        override fun speak(text: String, onDone: () -> Unit) {
            spoken += text
            mutableState.value = mutableState.value.copy(playing = true)
        }
        override fun pause() { mutableState.value = mutableState.value.copy(playing = false) }
        override fun changeSpeed(): Float { pause(); return state.value.speed }
    }
}
