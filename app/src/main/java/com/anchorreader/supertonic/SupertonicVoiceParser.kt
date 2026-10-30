package com.anchorreader.supertonic

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SupertonicVoiceParser(
    val numSpeakers: Int,
    private val ttlDims: IntArray, // [50, 256]
    private val dpDims: IntArray,  // [8, 16]
    private val ttlData: FloatArray, // 10 * 50 * 256
    private val dpData: FloatArray,  // 10 * 8 * 16
) {
    data class VoiceStyleSlice(
        val ttlData: FloatArray,
        val ttlShape: LongArray, // [1, 50, 256]
        val dpData: FloatArray,
        val dpShape: LongArray,  // [1, 8, 16]
    )

    fun getVoiceSlice(sid: Int): VoiceStyleSlice {
        val safeSid = sid.coerceIn(0, numSpeakers - 1)
        val ttlSliceSize = ttlDims[0] * ttlDims[1]
        val ttlOffset = safeSid * ttlSliceSize
        val ttlSlice = FloatArray(ttlSliceSize)
        System.arraycopy(ttlData, ttlOffset, ttlSlice, 0, ttlSliceSize)

        val dpSliceSize = dpDims[0] * dpDims[1]
        val dpOffset = safeSid * dpSliceSize
        val dpSlice = FloatArray(dpSliceSize)
        System.arraycopy(dpData, dpOffset, dpSlice, 0, dpSliceSize)

        return VoiceStyleSlice(
            ttlData = ttlSlice,
            ttlShape = longArrayOf(1, ttlDims[0].toLong(), ttlDims[1].toLong()),
            dpData = dpSlice,
            dpShape = longArrayOf(1, dpDims[0].toLong(), dpDims[1].toLong()),
        )
    }

    companion object {
        const val HEADER_BYTES = 48
        const val EXPECTED_FILE_SIZE = 517_168

        fun sidForVoice(name: String): Int {
            val idx = SupertonicManifest.VOICES.indexOf(name)
            return if (idx >= 0) idx else 0
        }

        fun voiceForSid(sid: Int): String {
            val safe = sid.coerceIn(0, SupertonicManifest.VOICES.size - 1)
            return SupertonicManifest.VOICES[safe]
        }

        fun parse(bytes: ByteArray): SupertonicVoiceParser {
            require(bytes.size == EXPECTED_FILE_SIZE) {
                "Invalid voice.bin size: expected $EXPECTED_FILE_SIZE bytes, got ${bytes.size}"
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val d0 = buf.long.toInt() // 10
            val d1 = buf.long.toInt() // 50
            val d2 = buf.long.toInt() // 256
            val d3 = buf.long.toInt() // 10
            val d4 = buf.long.toInt() // 8
            val d5 = buf.long.toInt() // 16

            require(d0 == 10 && d1 == 50 && d2 == 256 && d3 == 10 && d4 == 8 && d5 == 16) {
                "Invalid voice.bin header dimensions: ($d0, $d1, $d2, $d3, $d4, $d5)"
            }

            val ttlElements = d0 * d1 * d2 // 128,000 floats = 512,000 bytes
            val dpElements = d3 * d4 * d5  // 1,280 floats = 5,120 bytes

            val ttlArray = FloatArray(ttlElements)
            for (i in 0 until ttlElements) {
                ttlArray[i] = buf.float
            }

            val dpArray = FloatArray(dpElements)
            for (i in 0 until dpElements) {
                dpArray[i] = buf.float
            }

            return SupertonicVoiceParser(
                numSpeakers = d0,
                ttlDims = intArrayOf(d1, d2),
                dpDims = intArrayOf(d4, d5),
                ttlData = ttlArray,
                dpData = dpArray,
            )
        }

        fun parse(file: File): SupertonicVoiceParser = parse(file.readBytes())
    }
}
