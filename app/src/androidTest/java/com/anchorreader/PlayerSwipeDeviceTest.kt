package com.anchorreader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anchorreader.data.Book
import com.anchorreader.data.Concept
import com.anchorreader.data.Progress
import com.anchorreader.ui.AnchorTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSwipeDeviceTest {
    @get:Rule val compose = createComposeRule()

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
}
