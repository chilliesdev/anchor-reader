package com.anchorreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechBuffersTest {
    @Test
    fun preservesOneLongSentenceAcrossDeviceBuffers() {
        val text = "A complete sentence with many words ".repeat(400) + "ends here."
        val buffers = SpeechBuffers.split(text, 4000)
        assertTrue(buffers.size > 1)
        assertEquals(text, buffers.joinToString(""))
        assertTrue(buffers.all { it.length <= 4000 })
    }

    @Test
    fun preservesWordsWithoutSpacesAndUnicodePairs() {
        val text = "x".repeat(17) + "😀" + "y".repeat(30)
        val buffers = SpeechBuffers.split(text, 18)
        assertEquals(text, buffers.joinToString(""))
        assertTrue(buffers.all { it.length <= 18 })
        assertFalse(buffers.any { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }
}
