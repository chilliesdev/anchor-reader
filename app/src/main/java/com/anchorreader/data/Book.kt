package com.anchorreader.data

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val units: List<Concept>,
    val sourceText: String,
    val pdfPath: String? = null,
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val documentVersion: String = sourceHash(sourceText + units.joinToString { it.id })
}

data class Concept(
    val title: String,
    val sentences: List<String>,
    val sourcePage: Int? = null,
    val sourceSpans: List<SourceSpan> = emptyList(),
    val sourceStartOffset: Int = 0,
) {
    val id: String = "section-" + sourceHash(title + "\n" + sentences.joinToString(" ") + "\n" + sourcePage + ":" + sourceStartOffset).take(24)
}

/** Offsets refer to the section's normalized source, not narration buffers. */
data class SourceSpan(val page: Int, val text: String, val startOffset: Int, val endOffset: Int)

fun sourceHash(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

data class RecallQuestion(
    val unitIndex: Int,
    val prompt: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
    val id: String = "",
    val version: String = "",
    val sourcePage: Int? = null,
    val sourceExcerpt: String = "",
)

enum class Outcome { CORRECT, WRONG, SKIPPED }

data class Progress(
    val unitIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val visited: Set<Int> = emptySet(),
    val answers: Map<Int, Outcome> = emptyMap(),
    val completed: Boolean = false,
)
