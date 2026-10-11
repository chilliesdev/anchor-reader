package com.anchorreader

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
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
    fun heldDragPreviewsAdjacentSentenceAndKeepsControlsFixedUntilSettled() {
        val state = player()
        val before = compose.onNodeWithTag("current_sentence").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("sentence_count").fetchSemanticsNode().boundsInRoot
        screenshot("gesture-start")
        compose.onNodeWithTag("concept_video").performTouchInput {
            down(Offset(width * .4f, height * .8f))
            moveBy(Offset(0f, -height * .6f), delayMillis = 700)
        }
        compose.waitForIdle()
        val dragged = compose.onNodeWithTag("current_sentence").fetchSemanticsNode().boundsInRoot
        assertTrue("The sentence must track the finger before release", dragged.top < before.top - 100f)
        compose.onNodeWithTag("preview_sentence_1").assertIsDisplayed()
        assertEquals(controls, compose.onNodeWithTag("sentence_count").fetchSemanticsNode().boundsInRoot)
        assertEquals(0, state.unit)
        assertEquals(emptyList<Int>(), state.commits)
        screenshot("held-drag")
        compose.onNodeWithTag("concept_video").performTouchInput { advanceEventTime(400); up() }
        compose.waitForIdle()
        assertEquals(listOf(1), state.commits)
        compose.onNodeWithText("CONCEPT 2 OF 3").assertExists()
        compose.onNodeWithTag("current_sentence").assertTextEquals("Sentence in second concept.")
        screenshot("gesture-settled")
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
        compose.onNodeWithTag("concept_video").performTouchInput {
            down(Offset(width * .4f, height * .7f))
            moveBy(Offset(0f, -height * .6f), delayMillis = 700)
            cancel()
        }
        compose.waitForIdle()
        assertEquals("ACTION_CANCEL selected a concept", 0, state.unit)
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
    fun longSentenceScrollsAndVideoBackgroundAndLowerControlsSwipe() {
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
        val before = compose.onNodeWithTag("current_sentence").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("concept_video").performTouchInput {
            swipe(start = Offset(width * .3f, height * .6f), end = Offset(width * .3f, height * .35f))
        }
        compose.onNodeWithText("CONCEPT 1 OF 2").assertExists()
        val after = compose.onNodeWithTag("current_sentence").fetchSemanticsNode().boundsInRoot.top
        assertTrue("A complete long sentence cannot scroll", after < before)
        // Start a swipe on the counter beside Play. This remains reliable while text can scroll.
        compose.onNodeWithTag("sentence_count").performTouchInput {
            swipe(start = center, end = center.copy(y = center.y - 300f))
        }
        compose.onNodeWithText("CONCEPT 2 OF 2").assertExists()
        // The clear background above the short sentence also navigates in the other direction.
        compose.onNodeWithTag("concept_video").performTouchInput {
            swipe(start = Offset(width * .5f, height * .15f), end = Offset(width * .5f, height * .4f))
        }
        compose.onNodeWithText("CONCEPT 1 OF 2").assertExists()
        compose.onNodeWithTag("concept_video").performTouchInput {
            swipe(start = Offset(width * .5f, height * .15f), end = Offset(width * .5f, height * .4f))
        }
        compose.onNodeWithText("CONCEPT 1 OF 2").assertExists()
    }

    private fun player(): PlayerState {
        val state = PlayerState()
        compose.setContent {
            AnchorTheme {
                PlayerScreen(state.book, Progress(unitIndex = state.unit), VoiceState(ready = true, initializing = false), true, false,
                    onBack = {}, onTranscript = {}, onPlay = {}, onReplay = {}, onSpeed = {},
                    onPrevious = { state.unit = (state.unit - 1).coerceAtLeast(0) },
                    onNext = { state.unit = (state.unit + 1).coerceAtMost(2) },
                    onConceptSettled = { state.commits += it; state.unit = it },
                    onCheck = null, onStill = {}, onSource = {}, onVoiceSettings = {}, onPreviousSentence = {}, onNextSentence = {})
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
        var book by mutableStateOf(Book("swipe", "Swipe test", "QA", listOf(
            Concept("First concept", listOf("Sentence in first concept.")),
            Concept("Second concept", listOf("Sentence in second concept.")),
            Concept("Third concept", listOf("Sentence in third concept.")),
        ), "Original text"))
        val commits = mutableListOf<Int>()
    }
}
