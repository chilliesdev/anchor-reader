package com.anchorreader.supertonic

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer

class SupertonicTextFrontend(private val indexer: IntArray) {

    fun preprocess(text: String, lang: String = "en"): String {
        // 1. Unicode NFKD decomposition
        var s = Normalizer.normalize(text, Normalizer.Form.NFKD)

        // 2. Standardize typographic quotes and dashes
        s = s.replace(Regex("[‘’’‚‛′‵]"), "'")
            .replace(Regex("[“”„‟«»″‹›]"), "\"")
            .replace(Regex("[–—―−]"), "-")

        // 3. Drop spaces before punctuation
        s = s.replace(Regex("\\s+([,.:;!?'\"])"), "$1")

        // 4. Collapse adjacent duplicate quotes
        s = s.replace(Regex("\"+"), "\"").replace(Regex("'+"), "'").replace("`", "")

        // 5. Collapse whitespace and trim
        s = s.replace(Regex("\\s+"), " ").trim()

        if (s.isEmpty()) return ""

        // 6. Ensure ending punctuation
        val last = s.last()
        val endsWithPunct = last in setOf('.', '!', '?', ';', ':', ',', '\'', '"', ')', ']', '}')
        if (!endsWithPunct) {
            s += "."
        }

        // 7. Wrap with language tags
        return "<$lang>$s</$lang>"
    }

    fun encode(preprocessedText: String): Pair<LongArray, FloatArray> {
        val tokens = LongArray(preprocessedText.length)
        for (i in preprocessedText.indices) {
            val code = preprocessedText[i].code
            tokens[i] = if (code in indexer.indices) indexer[code].toLong() else 0L
        }
        val mask = FloatArray(tokens.size) { 1.0f }
        return tokens to mask
    }

    companion object {
        const val MAX_CHUNK_LEN = 280

        fun loadIndexer(bytes: ByteArray): IntArray {
            require(bytes.size >= 65536 * 4) { "Invalid unicode_indexer.bin: expected at least ${65536 * 4} bytes, got ${bytes.size}" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val indexer = IntArray(65536)
            for (i in 0 until 65536) {
                indexer[i] = buffer.int
            }
            return indexer
        }

        fun splitClauses(text: String, maxLen: Int = MAX_CHUNK_LEN): List<String> {
            val trimmed = text.trim()
            if (trimmed.length <= maxLen) return if (trimmed.isEmpty()) emptyList() else listOf(trimmed)

            val chunks = mutableListOf<String>()
            var remaining = trimmed
            while (remaining.length > maxLen) {
                val window = remaining.substring(0, maxLen)
                // Look for natural clause boundary: punctuation followed by space, or dash/comma
                var splitIdx = -1
                val punctuationDelimiters = listOf("; ", ", ", ": ", " - ", "— ", ". ", "! ", "? ")
                for (delim in punctuationDelimiters) {
                    val idx = window.lastIndexOf(delim)
                    if (idx in 30 until maxLen) {
                        splitIdx = maxOf(splitIdx, idx + delim.length)
                    }
                }
                if (splitIdx == -1) {
                    val spaceIdx = window.lastIndexOf(' ')
                    if (spaceIdx > 30) {
                        splitIdx = spaceIdx + 1
                    } else {
                        splitIdx = maxLen
                    }
                }
                val chunk = remaining.substring(0, splitIdx).trim()
                if (chunk.isNotEmpty()) {
                    chunks.add(chunk)
                }
                remaining = remaining.substring(splitIdx).trim()
            }
            if (remaining.isNotEmpty()) {
                chunks.add(remaining)
            }
            return chunks
        }
    }
}
