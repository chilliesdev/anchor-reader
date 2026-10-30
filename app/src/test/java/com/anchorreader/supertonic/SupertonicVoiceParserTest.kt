package com.anchorreader.supertonic

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SupertonicVoiceParserTest {

    private fun createValidVoiceBin(): ByteArray {
        val totalBytes = 48 + 10 * 50 * 256 * 4 + 10 * 8 * 16 * 4
        val buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        // Header
        buf.putLong(10)
        buf.putLong(50)
        buf.putLong(256)
        buf.putLong(10)
        buf.putLong(8)
        buf.putLong(16)

        // TTL data: 128,000 floats
        for (speaker in 0 until 10) {
            for (i in 0 until 50 * 256) {
                buf.putFloat(speaker * 1000f + i)
            }
        }

        // DP data: 1,280 floats
        for (speaker in 0 until 10) {
            for (i in 0 until 8 * 16) {
                buf.putFloat(speaker * 10f + i)
            }
        }

        return buf.array()
    }

    @Test
    fun testParseValidVoiceBin() {
        val bytes = createValidVoiceBin()
        assertEquals(517_168, bytes.size)

        val parser = SupertonicVoiceParser.parse(bytes)
        assertEquals(10, parser.numSpeakers)

        // Voice F1 is speaker 0
        assertEquals(0, SupertonicVoiceParser.sidForVoice("F1"))
        val f1Slice = parser.getVoiceSlice(0)
        assertEquals(50 * 256, f1Slice.ttlData.size)
        assertEquals(8 * 16, f1Slice.dpData.size)
        assertArrayEquals(longArrayOf(1, 50, 256), f1Slice.ttlShape)
        assertArrayEquals(longArrayOf(1, 8, 16), f1Slice.dpShape)
        assertEquals(0f, f1Slice.ttlData[0], 0.001f)
        assertEquals(0f, f1Slice.dpData[0], 0.001f)

        // Voice M1 is speaker 5
        assertEquals(5, SupertonicVoiceParser.sidForVoice("M1"))
        val m1Slice = parser.getVoiceSlice(5)
        assertEquals(5000f, m1Slice.ttlData[0], 0.001f)
        assertEquals(50f, m1Slice.dpData[0], 0.001f)

        // Voice M5 is speaker 9
        assertEquals(9, SupertonicVoiceParser.sidForVoice("M5"))
        val m5Slice = parser.getVoiceSlice(9)
        assertEquals(9000f, m5Slice.ttlData[0], 0.001f)
        assertEquals(90f, m5Slice.dpData[0], 0.001f)
    }

    @Test
    fun testRejectTruncatedOrCorruptFile() {
        val truncated = ByteArray(100)
        assertThrows(IllegalArgumentException::class.java) {
            SupertonicVoiceParser.parse(truncated)
        }

        val invalidDims = ByteBuffer.allocate(517_168).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(5) // Invalid num speakers
            putLong(50)
            putLong(256)
            putLong(10)
            putLong(8)
            putLong(16)
        }.array()

        assertThrows(IllegalArgumentException::class.java) {
            SupertonicVoiceParser.parse(invalidDims)
        }
    }
}
