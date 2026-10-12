package com.anchorreader

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchorreader.data.Book
import com.anchorreader.data.Concept
import com.anchorreader.data.Progress
import com.anchorreader.ui.AnchorTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class PlayerSwipeDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun heldDragMovesCompleteStillPageAndPreviewsAdjacentConcept() = heldPage(still = true)

    @Test
    fun heldDragMovesCompleteVideoPageAndPreviewsAdjacentConcept() = heldPage(still = false)

    private fun heldPage(still: Boolean) {
        val state = player(still = still, sentence = 1)
        val tags = listOf("concept_background_0", "concept_header_0", "concept_art_0", "concept_title_0", "current_sentence", "concept_actions_0", "concept_footer_0", "sentence_count", "sentence_progress") + if (still) emptyList() else listOf("concept_background_video_0")
        val before = tags.associateWith { compose.onNodeWithTag(it).getUnclippedBoundsInRoot().top.value }
        val name = if (still) "still" else "video"
        compose.onNodeWithTag("sentence_count").assertTextEquals("2 / 2")
        screenshot("$name-gesture-start")
        compose.onNodeWithTag("concept_video").performTouchInput {
            down(Offset(width * .4f, height * .8f))
            moveBy(Offset(0f, -height * .6f), delayMillis = 700)
        }
        compose.waitForIdle()
        val displacement = compose.onNodeWithTag("concept_background_0").getUnclippedBoundsInRoot().top.value - before.getValue("concept_background_0")
        assertTrue("The complete page must track the finger before release", displacement < -100f)
        tags.forEach { tag ->
            assertEquals("$tag must move with its page", displacement, compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value - before.getValue(tag), 1f)
        }
        val outgoing = compose.onNodeWithTag("concept_page_0").getUnclippedBoundsInRoot()
        val incoming = compose.onNodeWithTag("concept_page_1").getUnclippedBoundsInRoot()
        assertEquals("Concept pages must meet without a gap", outgoing.bottom.value, incoming.top.value, 1f)
        compose.onNodeWithTag("concept_header_1").assertIsDisplayed()
        compose.onNodeWithTag("preview_sentence_1").assertIsDisplayed()
        compose.onNodeWithTag("preview_sentence_count_1").assertTextEquals("1 / 3")
        compose.onAllNodesWithContentDescription("Next concept").assertCountEquals(1)
        compose.onNodeWithContentDescription("Next concept").assertIsNotEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription(if (still) "Play narration" else "Pause narration").assertIsNotEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(0, state.unit)
        assertEquals(1, state.sentence)
        assertEquals(emptyList<Int>(), state.commits)
        assertEquals(emptyList<String>(), state.actions)
        screenshot("$name-held-drag")
        compose.onNodeWithTag("concept_video").performTouchInput { advanceEventTime(400); up() }
        compose.waitForIdle()
        assertEquals(listOf(1), state.commits)
        assertEquals(0, state.sentence)
        compose.onNodeWithText("CONCEPT 2 OF 3").assertExists()
        compose.onNodeWithTag("current_sentence").assertTextEquals("Sentence in second concept.")
        screenshot("$name-gesture-settled")
    }

    @Test
    fun stillToggleAndPageControlsRemainUsableAfterEachSettlement() {
        val state = player()
        compose.onNodeWithContentDescription("Turn off still mode").performClick()
        assertEquals(false, state.still)
        flingUp()
        assertEquals(1, state.unit)
        compose.onNodeWithContentDescription("Turn on still mode").performClick()
        assertEquals(true, state.still)
        flingDown()
        assertEquals(0, state.unit)
        compose.onNodeWithContentDescription("Play narration").performClick()
        assertEquals(listOf("play"), state.actions)
        assertEquals(listOf(1, 0), state.commits)
    }

    @Test
    fun shortDragReversalCancellationAndHorizontalMovementDoNotCommit() {
        val state = player()
        compose.onNodeWithTag("concept_video").performTouchInput {
            down(Offset(width * .4f, height * .75f))
            moveBy(Offset(0f, -height * .15f), delayMillis = 700)
            advanceEventTime(400)
            up()
        }
        compose.waitForIdle()
        assertEquals("Short slow drag selected a concept", 0, state.unit)
        compose.onNodeWithTag("concept_video").performTouchInput {
            down(Offset(width * .4f, height * .8f))
            moveBy(Offset(0f, -height * .6f), delayMillis = 700)
            moveBy(Offset(0f, height * .5f), delayMillis = 700)
            advanceEventTime(400)
            up()
        }
        compose.waitForIdle()
        assertEquals("Reversal selected a concept", 0, state.unit)
        screenshot("gesture-reversed")
        compose.onNodeWithTag("concept_video").performTouchInput {
            down(Offset(width * .4f, height * .7f))
            moveBy(Offset(0f, -height * .6f), delayMillis = 700)
            cancel()
        }
        compose.waitForIdle()
        assertEquals("ACTION_CANCEL selected a concept", 0, state.unit)
        screenshot("gesture-cancelled")
        compose.onNodeWithTag("concept_video").performTouchInput {
            swipe(start = Offset(width * .2f, height * .3f), end = Offset(width * .8f, height * .3f))
        }
        compose.waitForIdle()
        assertEquals(0, state.unit)
        assertEquals(emptyList<Int>(), state.commits)
        assertEquals(0f, compose.onNodeWithTag("concept_page_0").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test
    fun fastFlingsMoveOnePageAndBothEdgesStayBounded() {
        val state = player()
        flingDown()
        assertEquals(emptyList<Int>(), state.commits)
        compose.onNodeWithContentDescription("Next concept").performTouchInput {
            swipe(start = center, end = center.copy(y = center.y - 300f), durationMillis = 100)
        }
        compose.waitForIdle()
        compose.onNodeWithText("CONCEPT 2 OF 3").assertExists()
        assertEquals(listOf(1), state.commits)
        flingUp()
        compose.onNodeWithText("CONCEPT 3 OF 3").assertExists()
        flingUp()
        assertEquals(listOf(1, 2), state.commits)
        flingDown()
        assertEquals(listOf(1, 2, 1), state.commits)
    }

    @Test
    fun buttonAndExternalProgressSynchronizeWithoutExtraCommit() {
        val state = player()
        compose.onNodeWithContentDescription("Next concept").performClick()
        compose.onNodeWithText("CONCEPT 2 OF 3").assertExists()
        assertEquals(emptyList<Int>(), state.commits)
        compose.onNodeWithTag("concept_video").performTouchInput {
            down(Offset(width * .4f, height * .8f))
            moveBy(Offset(0f, -height * .6f), delayMillis = 700)
        }
        compose.runOnIdle { state.unit = 2 }
        compose.onNodeWithTag("concept_video").performTouchInput {
            moveBy(Offset(0f, height * .6f), delayMillis = 700)
            advanceEventTime(400)
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("current_sentence").assertTextEquals("Sentence in third concept.")
        assertEquals(emptyList<Int>(), state.commits)
        flingDown()
        assertEquals(listOf(1), state.commits)
        compose.runOnIdle { state.book = state.book.copy(id = "another-book"); state.unit = 0 }
        compose.onNodeWithTag("current_sentence").assertTextEquals("Sentence in first concept.")
        flingUp()
        assertEquals(listOf(1, 1), state.commits)
    }

    @Test
    fun longSentencePartsAndTextSwipeChangesWholeConceptDirectly() {
        val book = Book("long-swipe", "Long sentences", "QA", listOf(
            Concept("Long topic", listOf("This complete sentence has all its source words ".repeat(150) + "here.")),
            Concept("Next topic", listOf("The next topic starts here.")),
        ), "Original text")
        compose.setContent {
            var unit by remember { mutableIntStateOf(0) }
            AnchorTheme {
                PlayerScreen(book, Progress(unitIndex = unit), VoiceState(ready = true, initializing = false), true, false,
                    onBack = {}, onTranscript = {}, onPlay = {}, onReplay = {}, onSpeed = {},
                    onPrevious = { unit = (unit - 1).coerceAtLeast(0) }, onNext = { unit = (unit + 1).coerceAtMost(1) },
                    onConceptSettled = { unit = it },
                    onCheck = null, onStill = {}, onSource = {}, onVoiceSettings = {}, onPreviousSentence = {}, onNextSentence = {})
            }
        }
        compose.onNodeWithTag("sentence_part_count").assertTextContains("Part 1 of", substring = true)
        compose.onNodeWithTag("previous_sentence_part").assertIsNotEnabled()
        compose.onNodeWithTag("next_sentence_part").performClick()
        compose.onNodeWithTag("sentence_part_count").assertTextContains("Part 2 of", substring = true)
        compose.onNodeWithTag("sentence_count").assertTextEquals("1 / 1")
        compose.onNodeWithTag("concept_art_0").assertDoesNotExist()
        compose.onNodeWithTag("current_sentence").performTouchInput { swipeUp() }
        compose.onNodeWithText("CONCEPT 2 OF 2").assertExists()
        // The clear background above the short sentence also navigates in the other direction.
        compose.onNodeWithTag("concept_video").performTouchInput {
            swipe(start = Offset(width * .5f, height * .15f), end = Offset(width * .5f, height * .4f))
        }
        compose.onNodeWithText("CONCEPT 1 OF 2").assertExists()
        compose.onNodeWithTag("sentence_part_count").assertTextContains("Part 1 of", substring = true)
        compose.onNodeWithTag("concept_video").performTouchInput {
            swipe(start = Offset(width * .5f, height * .15f), end = Offset(width * .5f, height * .4f))
        }
        compose.onNodeWithText("CONCEPT 1 OF 2").assertExists()
    }

    @Test
    fun railOrderTitleAndSentenceProgressMatchCurrentConcept() {
        val state = player()
        compose.onNodeWithContentDescription("Quick check").assertIsNotEnabled()
        compose.onNodeWithText("Quick check").assertDoesNotExist()
        val labels = listOf("Previous concept", "Next concept", "Turn off still mode", "Open source", "Quick check")
        val bounds = labels.map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (first, second) ->
            assertEquals(first.left, second.left, 1f)
            assertTrue("Rail controls must remain vertical", first.bottom <= second.top)
        }
        val title = compose.onNodeWithTag("concept_title_0").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("sentence_progress").fetchSemanticsNode().boundsInRoot
        assertTrue(title.right < bounds.last().left)
        assertTrue(title.bottom <= bar.top)
        compose.onNodeWithTag("sentence_progress").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(.5f, 0f..1f, 1)))
        compose.onNodeWithContentDescription("Previous sentence").assertIsNotEnabled()
        compose.runOnIdle { state.sentence = 1 }
        compose.onNodeWithTag("sentence_count").assertTextEquals("2 / 2")
        compose.onNodeWithContentDescription("Next sentence").assertIsNotEnabled()
        compose.onNodeWithTag("sentence_progress").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 0f..1f, 1)))
        compose.onNodeWithContentDescription("Next concept").performClick()
        compose.onNodeWithTag("sentence_count").assertTextEquals("1 / 3")
        compose.onNodeWithTag("sentence_progress").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f / 3, 0f..1f, 2)))
    }

    private fun player(still: Boolean = true, sentence: Int = 0): PlayerState {
        val state = PlayerState().apply { this.still = still; this.sentence = sentence }
        compose.setContent {
            AnchorTheme {
                PlayerScreen(state.book, Progress(unitIndex = state.unit, sentenceIndex = state.sentence), VoiceState(ready = true, initializing = false, playing = !state.still), state.still, false,
                    onBack = {}, onTranscript = {}, onPlay = { state.actions += "play" }, onReplay = {}, onSpeed = {},
                    onPrevious = { state.unit = (state.unit - 1).coerceAtLeast(0); state.sentence = 0 },
                    onNext = { state.unit = (state.unit + 1).coerceAtMost(2); state.sentence = 0 },
                    onConceptSettled = { state.commits += it; state.unit = it; state.sentence = 0 },
                    onCheck = null, onStill = { state.still = !state.still }, onSource = {}, onVoiceSettings = {}, onPreviousSentence = {}, onNextSentence = {})
            }
        }
        return state
    }

    private fun flingUp() {
        compose.onNodeWithTag("concept_video").performTouchInput {
            swipe(start = Offset(width * .4f, height * .9f), end = Offset(width * .4f, height * .1f), durationMillis = 100)
        }
        compose.waitForIdle()
    }

    private fun flingDown() {
        compose.onNodeWithTag("concept_video").performTouchInput {
            swipe(start = Offset(width * .4f, height * .1f), end = Offset(width * .4f, height * .9f), durationMillis = 100)
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("qa-swipe"))
        directory.mkdirs()
        FileOutputStream(File(directory, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private class PlayerState {
        var unit by mutableIntStateOf(0)
        var sentence by mutableIntStateOf(0)
        var still by mutableStateOf(true)
        var book by mutableStateOf(Book("swipe", "Swipe test", "QA", listOf(
            Concept("First concept", listOf("Sentence in first concept.", "Another sentence in first concept.")),
            Concept("Second concept", listOf("Sentence in second concept.", "Another second sentence.", "Third second sentence.")),
            Concept("Third concept", listOf("Sentence in third concept.")),
        ), "Original text", isSample = true))
        val commits = mutableListOf<Int>()
        val actions = mutableListOf<String>()
    }
}
