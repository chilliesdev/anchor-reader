package com.anchorreader

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses the installed Android speech service. A missing offline voice is reported as skipped audio QA. */
@RunWith(AndroidJUnit4::class)
class NarratorDeviceTest {
    private lateinit var narrator: Narrator
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun initializeVoice() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        instrumentation.runOnMainSync { narrator = Narrator(instrumentation.targetContext) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (narrator.state.value.status == "Starting device voice…" && System.nanoTime() < deadline) {
            Thread.sleep(100)
        }
        Log.i("AnchorReaderQA", "Speech prerequisite: ${narrator.state.value.status}")
    }

    @After
    fun closeVoice() {
        instrumentation.runOnMainSync { narrator.close() }
        scenario.close()
    }

    @Test
    fun voiceInitializationHasAUsableOrClearUnavailableState() {
        val state = narrator.state.value
        assertFalse("Speech initialization timed out.", state.status == "Starting device voice…")
        if (state.ready) assertEquals("Offline device voice ready", state.status)
        else assertTrue("Missing voice must give a useful message.", state.status.contains("voice", ignoreCase = true))
        assertFalse(state.playing)
    }

    @Test
    fun installedOfflineVoiceCompletesRealSpeechAndPauseCancelsIt() {
        assumeTrue("Offline English voice unavailable: ${narrator.state.value.status}", narrator.state.value.ready)
        val completed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            narrator.speak("Anchor Reader can read a saved lesson without a network.") { completed.countDown() }
        }
        assertTrue("Android speech service did not complete narration.", completed.await(20, TimeUnit.SECONDS))
        assertFalse(narrator.state.value.playing)
        assertTrue(narrator.state.value.ready)

        val cancelled = CountDownLatch(1)
        instrumentation.runOnMainSync {
            narrator.speak("This long reading must stop when the learner presses pause. ".repeat(12)) { cancelled.countDown() }
            assertTrue(narrator.state.value.playing)
            narrator.pause()
        }
        assertFalse(narrator.state.value.playing)
        assertFalse("Paused narration must not advance the lesson.", cancelled.await(500, TimeUnit.MILLISECONDS))
        instrumentation.runOnMainSync { narrator.changeSpeed() }
        assertEquals(1.25f, narrator.state.value.speed, .001f)
    }
}
