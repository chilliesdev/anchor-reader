package com.anchorreader

import androidx.activity.compose.setContent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.compose.ui.geometry.Offset
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
        compose.onNodeWithContentDescription("Quick check").performClick()
        compose.waitUntil(10_000) { model.state.value.screen == Screen.CHECK }
    }

    @Test
    fun previewAndCancelledSwipeDoNotRequestSpeechAndSettleStartsOnce() {
        compose.runOnIdle { voice.ready(); model.start(countBook()) }
        compose.waitUntil(10_000) { voice.spoken.size == 1 }
        val request = model.state.value.playbackRequest
        val oldCompletion = voice.callbacks.first()
        compose.onNodeWithTag("concept_video").performTouchInput {
            down(Offset(width * .4f, height * .8f))
            moveBy(Offset(0f, -height * .6f), delayMillis = 700)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("preview_sentence_1").assertIsDisplayed()
        assertEquals(0, model.state.value.currentProgress.unitIndex)
        assertEquals(request, model.state.value.playbackRequest)
        assertEquals(1, voice.spoken.size)
        compose.onNodeWithTag("concept_video").performTouchInput {
            moveBy(Offset(0f, height * .5f), delayMillis = 700)
            advanceEventTime(400)
            up()
        }
        compose.waitForIdle()
        assertEquals(request, model.state.value.playbackRequest)
        assertEquals(1, voice.spoken.size)
        compose.onNodeWithTag("concept_video").performTouchInput {
            down(Offset(width * .4f, height * .8f))
            moveBy(Offset(0f, -height * .6f), delayMillis = 700)
            cancel()
        }
        compose.waitForIdle()
        assertEquals(request, model.state.value.playbackRequest)
        assertEquals(1, voice.spoken.size)
        compose.onNodeWithTag("concept_video").performTouchInput {
            swipe(start = Offset(width * .4f, height * .75f), end = Offset(width * .4f, height * .4f), durationMillis = 100)
        }
        compose.waitUntil(10_000) { model.state.value.currentProgress.unitIndex == 1 && voice.spoken.size == 2 }
        compose.waitForIdle()
        assertEquals(request + 1, model.state.value.playbackRequest)
        compose.runOnIdle { oldCompletion() }
        assertEquals(0, model.state.value.currentProgress.sentenceIndex)
        assertEquals(2, voice.spoken.size)
    }

    @Test
    fun nativeStillToggleImmediatelyFollowedBySwipeSelectsConcept() {
        compose.runOnIdle { voice.ready(); model.setStill(true); model.start(SampleLesson.book) }
        compose.waitUntil(10_000) { voice.spoken.size == 1 }
        compose.onNodeWithContentDescription("Pause narration").performClick()
        val frame = compose.onNodeWithTag("concept_video").fetchSemanticsNode().boundsInRoot
        val toggle = compose.onNodeWithContentDescription("Turn off still mode").fetchSemanticsNode().boundsInRoot.center
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val x = frame.left + frame.width * .4f
        val start = frame.top + frame.height * .75f
        val end = frame.top + frame.height * .15f
        val lastMove = AtomicReference<Float?>()
        val inputError = AtomicReference<Throwable?>()
        var originalCallback: Window.Callback? = null
        scenario.onActivity { activity ->
            val original = activity.window.callback
            originalCallback = original
            activity.window.callback = object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_MOVE) lastMove.set(event.y)
                    return original.dispatchTouchEvent(event)
                }
            }
        }
        fun inject(action: Int, down: Long, touchX: Float, touchY: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, touchX, touchY, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(event, false)) { "Native touch injection failed" } }
            finally { event.recycle() }
        }
        // Queue native samples on real time. Shell input swipe can exhaust its duration
        // while waiting for DOWN and then send UP with no MOVE at the requested endpoint.
        val input = thread(name = "native-toggle-swipe") {
            var held: Long? = null
            try {
                val tap = SystemClock.uptimeMillis()
                held = tap
                inject(MotionEvent.ACTION_DOWN, tap, toggle.x, toggle.y)
                inject(MotionEvent.ACTION_UP, tap, toggle.x, toggle.y)
                held = null
                val down = SystemClock.uptimeMillis()
                held = down
                inject(MotionEvent.ACTION_DOWN, down, x, start)
                for (step in 1..25) {
                    Thread.sleep((down + step * 16 - SystemClock.uptimeMillis()).coerceAtLeast(0))
                    inject(MotionEvent.ACTION_MOVE, down, x, start + (end - start) * step / 25)
                }
                inject(MotionEvent.ACTION_UP, down, x, end)
                held = null
            } catch (failure: Throwable) { inputError.set(failure) }
            finally { held?.let { inject(MotionEvent.ACTION_CANCEL, it, x, end) } }
        }
        try {
            // Keep virtual animation frames running while native input uses wall time.
            compose.waitUntil(10_000) { inputError.get() != null || (!input.isAlive && model.state.value.currentProgress.unitIndex == 1) }
            inputError.get()?.let { throw AssertionError("Native input injection", it) }
            assertEquals("The native swipe must deliver its full MOVE path before UP", end, requireNotNull(lastMove.get()), 1f)
        } finally {
            input.interrupt()
            input.join(1_000)
            scenario.onActivity { it.window.callback = originalCallback }
        }
        compose.waitForIdle()
        assertFalse(model.state.value.stillMode)
        assertEquals(0, model.state.value.currentProgress.sentenceIndex)
        assertEquals(2, voice.spoken.size)
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
