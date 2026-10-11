package com.anchorreader

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchorreader.data.BookRepository
import com.anchorreader.data.SampleLesson
import com.anchorreader.ui.AnchorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled voice readiness and completion; real offline speech has separate device tests. */
@RunWith(AndroidJUnit4::class)
class PlaybackIntentDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: ReaderViewModel
    private val voice = ControlledNarration()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun launch() {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.getSharedPreferences("preferences", 0).edit().clear().commit()
        BookRepository(context).saveText("Sentence counts", "QA", """
            First topic

            First sentence. Second sentence. Third sentence.

            Second topic

            Fourth sentence. Fifth sentence. Sixth sentence. Seventh sentence. Eighth sentence.
        """.trimIndent())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            model = ReaderViewModel(it.application)
            it.setContent { AnchorTheme { ReaderApp(voice, model) } }
        }
        compose.waitUntil(10_000) { !model.state.value.loading }
    }

    @After
    fun close() { scenario.close() }

    @Test
    fun readyVoiceAutoplaysAndStopsAtFinalSentence() {
        compose.runOnIdle { voice.ready(); model.start(countBook()) }
        compose.waitUntil(10_000) { voice.spoken.size == 1 }
        compose.onNodeWithTag("sentence_count").assertTextEquals("1 / 3")
        compose.runOnIdle { voice.complete() }
        compose.onNodeWithTag("sentence_count").assertTextEquals("2 / 3")
        compose.runOnIdle { voice.complete() }
        compose.onNodeWithTag("sentence_count").assertTextEquals("3 / 3")
        compose.runOnIdle { voice.complete() }
        compose.onNodeWithContentDescription("Play narration").assertIsEnabled()
        assertEquals(0, model.state.value.currentProgress.unitIndex)
        assertEquals(3, voice.spoken.size)
    }

    @Test
    fun delayedVoiceStartsOnceAndPendingPauseCancelsIt() {
        compose.runOnIdle { model.start(countBook()) }
        compose.onNodeWithContentDescription("Pause narration").performClick()
        compose.runOnIdle { voice.ready() }
        compose.waitForIdle()
        assertEquals(0, voice.spoken.size)
        compose.onNodeWithContentDescription("Play narration").performClick()
        compose.waitUntil(10_000) { voice.spoken.size == 1 }
        compose.onNodeWithContentDescription("Pause narration").performClick()
        compose.runOnIdle { model.start(countBook(), 1); voice.mutableState.value = VoiceState() }
        compose.waitForIdle()
        assertEquals(1, voice.spoken.size)
        compose.runOnIdle { voice.ready() }
        compose.waitUntil(10_000) { voice.spoken.size == 2 }
        compose.waitForIdle()
        assertEquals(2, voice.spoken.size)
        compose.onNodeWithTag("sentence_count").assertTextEquals("1 / 5")
    }

    @Test
    fun swipeStartsAdjacentConceptAndRejectsOldCompletion() {
        compose.runOnIdle { voice.ready(); model.start(countBook()) }
        compose.waitUntil(10_000) { voice.spoken.size == 1 }
        val oldCompletion = voice.callbacks.first()
        compose.onNodeWithTag("current_sentence").performTouchInput { swipe(start = center, end = center.copy(y = center.y - 300f)) }
        compose.waitUntil(10_000) { model.state.value.currentProgress.unitIndex == 1 && voice.spoken.size == 2 }
        compose.onNodeWithTag("sentence_count").assertTextEquals("1 / 5")
        compose.onNodeWithTag("current_sentence").performTouchInput { swipe(start = center, end = center.copy(y = center.y + 300f)) }
        compose.waitUntil(10_000) { model.state.value.currentProgress.unitIndex == 0 && voice.spoken.size == 3 }
        compose.runOnIdle { oldCompletion() }
        compose.onNodeWithTag("sentence_count").assertTextEquals("1 / 3")
        assertEquals(3, voice.spoken.size)
        compose.runOnIdle { model.start(SampleLesson.book, 1) }
        compose.waitUntil(10_000) { voice.spoken.size == 4 }
        compose.onNodeWithContentDescription("Next concept").performClick()
        compose.waitUntil(10_000) { model.state.value.currentProgress.unitIndex == 2 }
        assertEquals(Screen.PLAYER, model.state.value.screen)
        compose.onNodeWithText("Quick check").performClick()
        compose.waitUntil(10_000) { model.state.value.screen == Screen.CHECK }
    }

    @Test
    fun sourceTranscriptAndBackgroundReturnRemainPaused() {
        compose.runOnIdle { voice.ready(); model.start(SampleLesson.book) }
        compose.waitUntil(10_000) { voice.spoken.size == 1 }
        compose.onNodeWithContentDescription("Open source").performClick()
        compose.onNodeWithText("Back to lesson").performClick()
        compose.onNodeWithContentDescription("Play narration").assertIsEnabled()
        compose.onNodeWithContentDescription("Play narration").performClick()
        compose.onNodeWithContentDescription("Read full transcript").performClick()
        compose.onNodeWithContentDescription("Close transcript").performClick()
        compose.onNodeWithContentDescription("Play narration").assertIsEnabled()
        compose.onNodeWithContentDescription("Play narration").performClick()
        val count = voice.spoken.size
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertFalse(voice.state.value.playing)
        assertEquals(count, voice.spoken.size)
        compose.onNodeWithContentDescription("Play narration").assertIsEnabled()
    }

    private fun countBook() = model.state.value.books.first { it.title == "Sentence counts" }

    private class ControlledNarration : Narration {
        val mutableState = MutableStateFlow(VoiceState())
        override val state = mutableState
        val spoken = mutableListOf<String>()
        val callbacks = mutableListOf<() -> Unit>()
        private var completion: (() -> Unit)? = null
        fun ready() { mutableState.value = mutableState.value.copy(ready = true, initializing = false) }
        override fun speak(text: String, onDone: () -> Unit) {
            spoken += text
            callbacks += onDone
            completion = onDone
            mutableState.value = mutableState.value.copy(playing = true)
        }
        fun complete() {
            val callback = completion
            completion = null
            mutableState.value = mutableState.value.copy(playing = false)
            callback?.invoke()
        }
        override fun pause() { completion = null; mutableState.value = mutableState.value.copy(playing = false) }
        override fun changeSpeed(): Float { pause(); return state.value.speed }
    }
}
