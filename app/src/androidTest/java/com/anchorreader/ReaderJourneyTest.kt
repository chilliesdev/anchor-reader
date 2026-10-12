package com.anchorreader

import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchorreader.data.BookRepository
import com.anchorreader.data.Outcome
import com.anchorreader.data.SampleLesson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** User flows use Compose semantics. Only the test app's local state is cleared. */
@RunWith(AndroidJUnit4::class)
class ReaderJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sample get() = SampleLesson.book

    @Before
    fun launchCleanApp() {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.getSharedPreferences("preferences", 0).edit().clear().commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(sample.title)
    }

    @After
    fun closeApp() { scenario.close() }

    @Test
    fun sampleJourneySavesAnswersAndStillMode() {
        compose.onNodeWithContentDescription("Start ${sample.title}").performScrollTo().performClick()
        waitForText(sample.units.first().sentences.first())
        compose.onNodeWithContentDescription("Previous concept").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Read full transcript").performClick()
        waitForText("FULL TEXT")
        compose.onNodeWithText(sample.units.first().sentences.joinToString(" ")).assertExists()
        compose.onNodeWithContentDescription("Close transcript").performClick()
        compose.onNodeWithContentDescription("Open source").performScrollTo().performClick()
        val sourceBack = compose.onNodeWithText("Back to lesson").assertIsDisplayed()
        val sourceBackBounds = sourceBack.fetchSemanticsNode().boundsInWindow
        val display = context.resources.displayMetrics
        var windowHeight = 0
        scenario.onActivity { windowHeight = it.window.decorView.height }
        assertTrue("Source button is clipped: $sourceBackBounds", sourceBackBounds.height >= 40 * display.density - 2)
        assertTrue("Source button is outside its window: $sourceBackBounds", sourceBackBounds.top >= 0 && sourceBackBounds.bottom <= windowHeight)
        sourceBack.performClick()
        waitForText(sample.units.first().sentences.first())
        if (compose.onAllNodesWithContentDescription("Turn off still mode").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Turn off still mode").performClick()
        }
        compose.onNodeWithContentDescription("Turn on still mode").performClick()
        compose.onNodeWithContentDescription("Turn off still mode").assertExists()
        compose.onNodeWithContentDescription("Next sentence").performClick()
        waitForText(sample.units.first().sentences[1])
        compose.onNodeWithContentDescription("Previous sentence").performClick()
        waitForText(sample.units.first().sentences.first())

        // The first concept has no recall check. Exercise both concept controls.
        compose.onNodeWithContentDescription("Next concept").performScrollTo().performClick()
        waitForText(sample.units[1].title)
        compose.onNodeWithContentDescription("Previous concept").assertIsEnabled().performClick()
        waitForText(sample.units.first().title)

        DeviceEvidence.capture("sample-player-disabled-check")
        compose.onNodeWithContentDescription("Quick check").assertIsNotEnabled()
        compose.onNodeWithText("Quick check").assertDoesNotExist()
        compose.onNodeWithContentDescription("Next concept").performScrollTo().performClick()
        waitForText(sample.units[1].title)
        compose.onNodeWithContentDescription("Next concept").performScrollTo().performClick()
        waitForText(sample.units[2].title)
        compose.onNodeWithContentDescription("Previous concept").performScrollTo().performClick()
        waitForText(sample.units[1].title)
        compose.onNodeWithContentDescription("Quick check").assertIsEnabled().performClick()
        DeviceEvidence.capture("sample-check-question")
        val expected = mutableMapOf<Int, Outcome>()
        SampleLesson.questions.forEachIndexed { questionIndex, question ->
            waitForText(sample.units[question.unitIndex].title)
            compose.onNodeWithText("Quick check").assertDoesNotExist()
            compose.onNodeWithText("Check answer").assertIsNotEnabled()
            val outcome = when (questionIndex) {
                0 -> {
                    compose.onNodeWithText(question.options[question.correctIndex]).performScrollTo().performClick()
                    clickText("Check answer")
                    waitForText("Correct")
                    Outcome.CORRECT
                }
                1 -> {
                    val wrong = (question.correctIndex + 1) % question.options.size
                    compose.onNodeWithText(question.options[wrong]).performScrollTo().performClick()
                    clickText("Check answer")
                    waitForText("Review this idea")
                    Outcome.WRONG
                }
                else -> {
                    clickText("Skip")
                    waitForText("Skipped")
                    Outcome.SKIPPED
                }
            }
            expected[questionIndex] = outcome
            clickText(if (questionIndex < SampleLesson.questions.lastIndex) "Next question" else "Complete")
        }
        waitForText("You completed this check.")
        compose.onNodeWithText("3 / 4").assertExists()
        DeviceEvidence.capture("sample-check-results-partial-visits")
        compose.onNodeWithText("Answer record only. This is not a mastery score.").assertDoesNotExist()
        compose.onNodeWithText("Wrong · Review suggested").assertExists()
        compose.onNodeWithText("Not answered").assertDoesNotExist()
        compose.waitUntil(10_000) { BookRepository(context).loadProgress(sample.id).answers == expected }
        val saved = BookRepository(context).loadProgress(sample.id)
        assertEquals(setOf(0, 1, 2), saved.visited)
        assertFalse(saved.completed)
        assertEquals(expected, saved.answers)
        clickText("Back to library")
        waitForText(sample.title)
        DeviceEvidence.capture("sample-library-partial-visits")

        restart()
        assertEquals(expected, BookRepository(context).loadProgress(sample.id).answers)
        compose.onNodeWithContentDescription("Continue ${sample.title}").performScrollTo().performClick()
        waitForText(sample.units[1].sentences.first())
        compose.onNodeWithContentDescription("Turn off still mode").assertExists()
    }

    @Test
    fun createTextBookValidateSaveReadAndDelete() {
        compose.onNodeWithText("New").performClick()
        clickText("Create book")
        clickText("Save book")
        waitForText("Enter a title.")
        compose.onNodeWithTag("create_title").performTextInput("QA notebook")
        Espresso.closeSoftKeyboard()
        clickText("Save book")
        waitForText("Enter book text.")
        compose.onNodeWithTag("create_author").performTextInput("QA author")
        compose.onNodeWithTag("create_text").performTextInput("The source stays on this device. The reader can open it later.")
        Espresso.closeSoftKeyboard()
        clickText("Save book")
        waitForText("QA notebook")
        val saved = BookRepository(context).loadBooks().first { it.title == "QA notebook" }
        assertEquals("QA author", saved.author)
        assertFalse(saved.isSample)
        compose.onNodeWithContentDescription("Start QA notebook").performScrollTo().performClick()
        waitForText(saved.units.first().sentences.first())
        compose.onNodeWithContentDescription("Next concept").performScrollTo().performClick()
        waitForText("You reached the end of this book.")
        clickText("Back to library")
        restart()
        assertTrue(BookRepository(context).loadBooks().any { it.id == saved.id })
        compose.onNodeWithContentDescription("Details for QA notebook").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Delete QA notebook").performScrollTo().performClick()
        waitForText("Delete this book?")
        compose.onNode(hasText("Delete book") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { BookRepository(context).loadBooks().none { it.id == saved.id } }
        waitForText(sample.title)
        compose.onNodeWithText("QA notebook").assertDoesNotExist()
    }

    @Test
    fun pdfFormRequiresAFile() {
        compose.onNodeWithText("New").performClick()
        clickText("Import PDF")
        compose.onNodeWithTag("pdf_title").performTextInput("QA PDF")
        Espresso.closeSoftKeyboard()
        clickText("Save book")
        waitForText("Choose a PDF file.")
        assertFalse(BookRepository(context).loadBooks().any { it.title == "QA PDF" })
    }

    @Test
    fun landscapePlayerControlsAndDialogsRemainReachable() {
        val longTitle = "An offline reading source with a long title for layout checks ".repeat(3).take(120)
        val sourceText = "This saved source remains readable under its long title. The original words stay on this device."
        BookRepository(context).saveText(longTitle, "QA author", sourceText)
        restart()
        compose.onNodeWithContentDescription("Start ${sample.title}").performScrollTo().performClick()
        waitForText(sample.units.first().sentences.first())
        var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val previousConfiguration = context.resources.configuration.orientation
        scenario.onActivity {
            previousOrientation = it.requestedOrientation
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        try {
            compose.waitUntil(10_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            waitForText(sample.units.first().title)
            listOf("Read full transcript", "Open source", "Previous concept", "Next concept").forEach {
                val control = compose.onNodeWithContentDescription(it)
                if (it != "Read full transcript") control.performScrollTo()
                assertControlInsideWindow(control, it)
            }
            compose.onNodeWithContentDescription("Next concept").performScrollTo().performClick()
            waitForText(sample.units[1].title)
            compose.onNodeWithContentDescription("Previous concept").performScrollTo().performClick()
            waitForText(sample.units.first().title)
            compose.onNodeWithContentDescription("Open source").performScrollTo().performClick()
            val sourceBack = compose.onNodeWithText("Back to lesson")
            assertControlInsideWindow(sourceBack, "Source footer")
            assertTrue("Source footer is clipped in landscape", sourceBack.fetchSemanticsNode().boundsInWindow.height >= 40 * context.resources.displayMetrics.density - 2)
            sourceBack.performClick()
            compose.onNodeWithContentDescription("Read full transcript").performClick()
            waitForText("FULL TEXT")
            val closeTranscript = compose.onNodeWithContentDescription("Close transcript")
            assertControlInsideWindow(closeTranscript, "Transcript close")
            assertTrue("Transcript close icon is clipped", closeTranscript.fetchSemanticsNode().boundsInWindow.width >= 22 * context.resources.displayMetrics.density)
            closeTranscript.performClick()
            waitForText(sample.units.first().title)
            compose.onNodeWithContentDescription("Back to lesson outline").performClick()
            compose.onNodeWithText("Library").performClick()
            compose.onNodeWithContentDescription("Start $longTitle").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Open source").performScrollTo().performClick()
            compose.onNodeWithText(sourceText).performScrollTo().assertIsDisplayed()
            val textSourceBack = compose.onNodeWithText("Back to lesson")
            assertControlInsideWindow(textSourceBack, "Long-title source footer")
            textSourceBack.performClick()
            compose.onNodeWithContentDescription("Read full transcript").performClick()
            compose.onNodeWithText(sourceText).performScrollTo().assertIsDisplayed()
            val textTranscriptClose = compose.onNodeWithContentDescription("Close transcript")
            assertControlInsideWindow(textTranscriptClose, "Long-title transcript close")
            textTranscriptClose.performClick()
        } finally {
            scenario.onActivity { it.requestedOrientation = previousOrientation }
            compose.waitUntil(10_000) { context.resources.configuration.orientation == previousConfiguration }
        }
    }

    @Test
    fun videoMovesDuringSpeechAndStillBackgroundDoesNot() {
        compose.onNodeWithContentDescription("Start ${sample.title}").performScrollTo().performClick()
        waitForText(sample.units.first().sentences.first())
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasContentDescription("Play narration") and isEnabled()).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Starting device voice…").fetchSemanticsNodes().isEmpty()
        }
        val playable = compose.onAllNodes(hasContentDescription("Play narration") and isEnabled()).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodes(hasContentDescription("Pause narration") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        assumeTrue("An installed offline English voice is required for video playback QA.", playable)
        if (compose.onAllNodesWithContentDescription("Turn off still mode").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Turn off still mode").performClick()
        }
        if (compose.onAllNodesWithContentDescription("Play narration").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Play narration").performClick()
        Thread.sleep(800)
        val movingA = screenshot("video-a")
        Thread.sleep(1_000)
        val movingB = screenshot("video-b")
        val movingFraction = backgroundDifference(movingA, movingB)
        Log.i("AnchorReaderQA", "Moving background changed fraction=$movingFraction")
        assertTrue("Background video did not move during narration: $movingFraction", movingFraction > .01)
        compose.onNodeWithContentDescription("Turn on still mode").performClick()
        Thread.sleep(300)
        val stillA = screenshot("still-a")
        Thread.sleep(1_000)
        val stillB = screenshot("still-b")
        val stillFraction = backgroundDifference(stillA, stillB)
        Log.i("AnchorReaderQA", "Still background changed fraction=$stillFraction")
        assertTrue("Still mode background changed: $stillFraction", stillFraction < .001)
        movingA.recycle(); movingB.recycle(); stillA.recycle(); stillB.recycle()
    }

    private fun restart() {
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(sample.title)
    }

    private fun clickText(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
    }

    private fun assertControlInsideWindow(node: SemanticsNodeInteraction, label: String) {
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInWindow
        var width = 0
        var height = 0
        scenario.onActivity { width = it.window.decorView.width; height = it.window.decorView.height }
        assertTrue("$label is outside the window: $bounds ($width x $height)", bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height)
    }

    private fun waitForText(text: String) {
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("library_list").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithTag("library_list").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("library_list").performScrollToNode(hasText(text))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(text).onFirst().assertExists()
    }

    private fun screenshot(name: String): Bitmap {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = File(context.getExternalFilesDir(null), "qa-motion").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /** The top centre background excludes status text, player controls, and transcript. */
    private fun backgroundDifference(first: Bitmap, second: Bitmap): Double {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        var changed = 0
        var sampled = 0
        for (y in first.height * 12 / 100 until first.height * 20 / 100 step 4) {
            for (x in first.width / 4 until first.width * 3 / 4 step 4) {
                val a = first.getPixel(x, y)
                val b = second.getPixel(x, y)
                val distance = abs((a shr 16 and 255) - (b shr 16 and 255)) +
                    abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))
                if (distance >= 6) changed++
                sampled++
            }
        }
        return changed.toDouble() / sampled
    }
}
