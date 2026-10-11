package com.anchorreader

/** TTS buffer limits do not change the displayed sentence or its progress. */
internal object SpeechBuffers {
    fun split(text: String, maximum: Int): List<String> {
        require(maximum >= 2)
        if (text.isEmpty()) return emptyList()
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maximum).coerceAtMost(text.length)
            if (end < text.length) {
                val space = text.lastIndexOf(' ', end - 1)
                if (space > start) end = space + 1
                if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            parts += text.substring(start, end)
            start = end
        }
        return parts
    }
}
