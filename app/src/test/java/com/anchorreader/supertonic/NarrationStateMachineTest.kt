package com.anchorreader.supertonic

import com.anchorreader.VoiceState
import org.junit.Assert.*
import org.junit.Test

class NarrationStateMachineTest {

    @Test
    fun testVoiceStateInitialDefaults() {
        val defaultState = VoiceState()
        assertFalse(defaultState.ready)
        assertTrue(defaultState.initializing)
        assertFalse(defaultState.playing)
        assertEquals("Starting device voice…", defaultState.status)
        assertEquals(1f, defaultState.speed, 0.001f)
        assertEquals(VoicePackState.NotInstalled, defaultState.packState)
        assertTrue(defaultState.useHd)
        assertEquals("F1", defaultState.selectedVoice)
    }

    @Test
    fun testSystemVoiceStatusBackwardCompatibility() {
        // When system voice is ready
        val systemState = VoiceState(
            ready = true,
            initializing = false,
            playing = false,
            status = "Offline device voice ready",
            useHd = false,
        )
        assertTrue(systemState.ready)
        assertEquals("Offline device voice ready", systemState.status)
    }

    @Test
    fun testHdVoiceStatusFormat() {
        // When Supertonic HD is installed and enabled
        val hdState = VoiceState(
            ready = true,
            initializing = false,
            playing = false,
            status = "Offline HD voice ready · F1",
            packState = VoicePackState.Installed(128_774_318L),
            useHd = true,
            selectedVoice = "F1",
        )
        assertTrue(hdState.ready)
        assertEquals("Offline HD voice ready · F1", hdState.status)

        // Switching to M3
        val m3State = hdState.copy(
            selectedVoice = "M3",
            status = "Offline HD voice ready · M3"
        )
        assertEquals("Offline HD voice ready · M3", m3State.status)
    }

    @Test
    fun testSpeedChoicesCycling() {
        val choices = listOf(.75f, 1f, 1.25f, 1.5f, 2f)
        var speed = 1f
        speed = choices[(choices.indexOf(speed) + 1) % choices.size]
        assertEquals(1.25f, speed, 0.001f)
        speed = choices[(choices.indexOf(speed) + 1) % choices.size]
        assertEquals(1.5f, speed, 0.001f)
        speed = choices[(choices.indexOf(speed) + 1) % choices.size]
        assertEquals(2f, speed, 0.001f)
        speed = choices[(choices.indexOf(speed) + 1) % choices.size]
        assertEquals(0.75f, speed, 0.001f)
        speed = choices[(choices.indexOf(speed) + 1) % choices.size]
        assertEquals(1f, speed, 0.001f)
    }

    @Test
    fun testVoicePackLifecycleTransitions() {
        var state = VoiceState()
        assertEquals(VoicePackState.NotInstalled, state.packState)

        // Start download
        state = state.copy(packState = VoicePackState.Downloading(1024L, 128_774_318L))
        assertTrue(state.packState is VoicePackState.Downloading)
        assertEquals(1024L, (state.packState as VoicePackState.Downloading).bytesRead)

        // Installed
        state = state.copy(
            packState = VoicePackState.Installed(128_774_318L),
            ready = true,
            initializing = false,
            status = "Offline HD voice ready · F1"
        )
        assertTrue(state.packState is VoicePackState.Installed)
        assertEquals("Offline HD voice ready · F1", state.status)

        // Deleted
        state = state.copy(
            packState = VoicePackState.NotInstalled,
            status = "Offline device voice ready"
        )
        assertEquals(VoicePackState.NotInstalled, state.packState)
        assertEquals("Offline device voice ready", state.status)
    }
}
