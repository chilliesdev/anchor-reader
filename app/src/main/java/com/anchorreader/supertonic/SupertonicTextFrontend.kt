package com.anchorreader.supertonic

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer

class SupertonicTextFrontend(private val indexer: IntArray) {

    fun preprocess(text: String, lang: String = "en", isClause: Boolean = false): String {
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

        // Normalize trailing em/en/hyphen dashes so clauses do not receive spurious ".-" punctuation
        while (s.endsWith("-") || s.endsWith("–") || s.endsWith("—")) {
            s = s.dropLast(1).trimEnd()
        }

        if (s.isEmpty()) return ""

        // 6. Ensure ending punctuation only for full sentences or when not a streaming intra-clause
        val last = s.last()
        val endsWithPunct = last in setOf('.', '!', '?', ';', ':', ',', '\'', '"', ')', ']', '}')
        if (!endsWithPunct && !isClause) {
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

        fun splitInitialStreamingChunk(
            text: String,
            minWords: Int = 3,
            maxWords: Int = 5,
            minChars: Int = 15,
            maxChars: Int = 30
        ): Pair<String, String>? {
            val trimmed = text.trim()
            if (trimmed.length <= maxChars) return null

            val words = trimmed.split(Regex("\\s+"))
            if (words.size <= minWords) return null

            // 1. Check for punctuation boundaries within 3..5 words and minChars..maxChars
            val punctuationDelimiters = listOf("; ", ", ", ": ", " - ", " — ", ". ", "! ", "? ")
            val searchCap = minOf(trimmed.length, maxChars + 2)
            val window = trimmed.substring(0, searchCap)

            var bestPunctSplit: Pair<Int, Int>? = null
            for (delim in punctuationDelimiters) {
                var idx = window.indexOf(delim)
                while (idx != -1) {
                    val candidate = trimmed.substring(0, idx + if (delim.startsWith(" ")) 0 else 1).trim()
                    val candWords = candidate.split(Regex("\\s+")).size
                    val nextStart = idx + delim.length
                    if (candWords in minWords..maxWords && candidate.length in minChars..maxChars && nextStart < trimmed.length) {
                        bestPunctSplit = candidate.length to nextStart
                        break
                    }
                    idx = window.indexOf(delim, idx + 1)
                }
                if (bestPunctSplit != null) break
            }

            if (bestPunctSplit != null) {
                val initial = trimmed.substring(0, bestPunctSplit.first).trim()
                val remaining = trimmed.substring(bestPunctSplit.second).trim()
                if (initial.isNotEmpty() && remaining.isNotEmpty()) {
                    return initial to remaining
                }
            }

            // 2. Fall back to natural word boundaries within minWords..maxWords and minChars..maxChars
            var accumLength = 0
            var splitIndex = -1
            for (i in words.indices) {
                val word = words[i]
                val prevLen = accumLength
                accumLength += word.length + (if (i > 0) 1 else 0)
                val wordCount = i + 1

                if (wordCount in minWords..maxWords && accumLength in minChars..maxChars) {
                    splitIndex = accumLength
                    // Prefer 3 words if already within 15..30 chars for lowest TTFA
                    if (wordCount >= minWords) {
                        break
                    }
                } else if (accumLength > maxChars) {
                    if (splitIndex == -1 && wordCount > minWords) {
                        splitIndex = prevLen
                    }
                    break
                }
            }

            if (splitIndex in 1 until trimmed.length) {
                val initial = trimmed.substring(0, splitIndex).trim()
                val remaining = trimmed.substring(splitIndex).trim()
                if (initial.isNotEmpty() && remaining.isNotEmpty()) {
                    return initial to remaining
                }
            }

            return null
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

        fun splitStreamClauses(
            text: String,
            minClauseLen: Int = 24,
            maxLen: Int = MAX_CHUNK_LEN,
            initialStreamingChunk: Boolean = false
        ): List<String> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()

            if (initialStreamingChunk) {
                val initialSplit = splitInitialStreamingChunk(trimmed)
                if (initialSplit != null) {
                    val result = mutableListOf(initialSplit.first)
                    result.addAll(splitStreamClauses(initialSplit.second, minClauseLen, maxLen, initialStreamingChunk = false))
                    return result
                }
            }

            if (trimmed.length <= minClauseLen) return listOf(trimmed)

            val delimiters = listOf("; ", ": ", ". ", "! ", "? ", " — ", " – ", " - ", ", ")
            val chunks = mutableListOf<String>()
            var remaining = trimmed

            while (remaining.isNotEmpty()) {
                val searchLimit = minOf(remaining.length, maxLen)
                var splitPoint: Pair<Int, Int>? = null

                for (i in minClauseLen until searchLimit) {
                    for (delim in delimiters) {
                        if (remaining.startsWith(delim, i)) {
                            val clauseEnd = if (delim.startsWith(" ")) i else i + 1
                            val nextStart = i + delim.length
                            if (remaining.length - nextStart >= 16 || remaining.length > maxLen) {
                                splitPoint = clauseEnd to nextStart
                                break
                            }
                        }
                    }
                    if (splitPoint != null) break
                }

                if (splitPoint != null) {
                    val chunk = remaining.substring(0, splitPoint.first).trim()
                    if (chunk.isNotEmpty()) chunks.add(chunk)
                    remaining = remaining.substring(splitPoint.second).trim()
                } else if (remaining.length <= maxLen) {
                    chunks.add(remaining)
                    break
                } else {
                    val window = remaining.substring(0, maxLen)
                    var fallbackEnd = -1
                    var fallbackNext = -1
                    for (delim in delimiters) {
                        val idx = window.lastIndexOf(delim)
                        if (idx in 0 until maxLen) {
                            fallbackEnd = if (delim.startsWith(" ")) idx else idx + 1
                            fallbackNext = idx + delim.length
                            break
                        }
                    }
                    if (fallbackEnd == -1) {
                        val spaceIdx = window.lastIndexOf(' ')
                        if (spaceIdx > 0) {
                            fallbackEnd = spaceIdx
                            fallbackNext = spaceIdx + 1
                        } else {
                            fallbackEnd = maxLen
                            fallbackNext = maxLen
                        }
                    }
                    val chunk = remaining.substring(0, fallbackEnd).trim()
                    if (chunk.isNotEmpty()) chunks.add(chunk)
                    remaining = remaining.substring(fallbackNext).trim()
                }
            }
            return chunks
        }
    }
}
