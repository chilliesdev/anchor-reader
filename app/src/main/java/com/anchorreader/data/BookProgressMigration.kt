package com.anchorreader.data

/** Keeps the reader at the same source sentence when old paragraph units become topics. */
internal object BookProgressMigration {
    private data class Position(val unit: Int, val sentence: Int, val start: Int, val end: Int)
    private val whitespace = Regex("\\s+")

    fun remap(previous: Book, current: Book, progress: Progress): Progress {
        val clean = ProgressRules.normalize(previous, progress)
        val before = positions(previous)
        val after = positions(current)
        val anchor = before.firstOrNull { it.unit == clean.unitIndex && it.sentence == clean.sentenceIndex }
        val destination = anchor?.let { position ->
            after.firstOrNull { position.start in it.start until it.end }
                ?: after.firstOrNull { it.start >= position.start }
                ?: after.lastOrNull()
        }
        val visitedRanges = before.filter { it.unit in clean.visited }
        var rangeIndex = 0
        val visited = mutableSetOf<Int>()
        after.forEach { next ->
            while (rangeIndex < visitedRanges.size && visitedRanges[rangeIndex].end <= next.start) rangeIndex++
            if (rangeIndex < visitedRanges.size && visitedRanges[rangeIndex].start < next.end) visited += next.unit
        }
        return ProgressRules.normalize(current, clean.copy(
            unitIndex = destination?.unit ?: clean.unitIndex,
            sentenceIndex = destination?.sentence ?: clean.sentenceIndex,
            visited = visited,
        ))
    }

    fun restoreSourcePages(previous: Book, current: Book): Book {
        val before = positions(previous)
        val after = positions(current)
        var sourceIndex = 0
        return current.copy(units = current.units.mapIndexed { index, unit ->
            val first = after.firstOrNull { it.unit == index }
            val source = first?.let { position ->
                while (sourceIndex < before.size && before[sourceIndex].end <= position.start) sourceIndex++
                before.getOrNull(sourceIndex)?.takeIf { it.start < position.end }
            }
            unit.copy(sourcePage = source?.let { previous.units[it.unit].sourcePage } ?: unit.sourcePage)
        })
    }

    private fun positions(book: Book): List<Position> {
        val source = book.sourceText.replace(whitespace, " ").trim()
        var cursor = 0
        return buildList {
            book.units.forEachIndexed { unitIndex, unit ->
                unit.sentences.forEachIndexed { sentenceIndex, sentence ->
                    val normalized = sentence.replace(whitespace, " ").trim()
                    val start = source.indexOf(normalized, cursor)
                    if (start >= 0) {
                        val end = start + normalized.length
                        add(Position(unitIndex, sentenceIndex, start, end))
                        cursor = end
                    }
                }
            }
        }
    }
}
