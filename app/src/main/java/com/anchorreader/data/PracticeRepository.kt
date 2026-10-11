package com.anchorreader.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Separate atomic records keep network work outside the reading repository's write lock. */
class PracticeRepository(context: Context, private val books: BookRepository) {
    private val directory = File(context.filesDir, "practice").apply { check(exists() || mkdirs()) }
    private val canceledTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun fenceTask(taskId: String) { canceledTasks += taskId }

    @Synchronized
    fun load(book: Book, recoverInterrupted: Boolean = false): PracticeRecord {
        val file = file(book.id)
        if (!file.exists() && !File(file.path + ".bak").exists()) return PracticeRecord(book.documentVersion)
        val json = AtomicFile(file).openRead().use { stream ->
            require(stream.channel.size() <= 8L * 1024 * 1024) { "Practice storage is too large." }
            JSONObject(stream.bufferedReader().readText())
        }
        require(json.getInt("schema") == 1 && json.getString("bookId") == book.id) { "Invalid practice record." }
        if (json.getString("documentVersion") != book.documentVersion) return PracticeRecord(book.documentVersion)
        val questionsJson = json.getJSONArray("questions")
        val questions = (0 until questionsJson.length()).map { n ->
            val q = questionsJson.getJSONObject(n)
            val unit = q.getInt("unitIndex")
            require(unit in book.units.indices) { "Invalid question section." }
            val source = QuestionSource(book.units[unit].id, book.units[unit].sourceSpans, 3)
            val payload = q.getJSONObject("content")
            val parsed = QuestionValidator.parse(JSONObject().put("sectionId", source.sectionId)
                .put("questions", JSONArray().put(payload)).toString(), book, unit, source).single()
            require(parsed.id == q.getString("id") && parsed.version == q.getString("version")) { "Invalid question version." }
            parsed
        }
        require(questions.groupBy { it.unitIndex }.values.all { it.size <= 3 } && questions.map { it.id }.distinct().size == questions.size) { "Invalid saved question count." }
        val answersJson = json.getJSONObject("answers")
        val keys = questions.map(::answerKey).toSet()
        val answers = answersJson.keys().asSequence().filter { it in keys }.associateWith { Outcome.valueOf(answersJson.getString(it)) }
        val taskJson = json.optJSONObject("task")
        val task = taskJson?.let { data ->
            val sectionJson = data.getJSONObject("sections")
            val sectionIds = book.units.map { it.id }.toSet()
            val sections = sectionJson.keys().asSequence().associateWith { key ->
                require(key in sectionIds) { "Invalid task section." }
                SectionQuestionStatus.valueOf(sectionJson.getString(key))
            }
            QuestionTask(data.getString("id"), book.documentVersion, data.getString("accountId"),
                QuestionTaskStatus.valueOf(data.getString("status")), sections, data.optString("message"))
        }
        var record = PracticeRecord(book.documentVersion, questions, answers, task, json.optLong("revision", 0))
        if (recoverInterrupted && record.working) {
            record = record.copy(revision = record.revision + 1, task = task!!.copy(status = QuestionTaskStatus.PAUSED,
                message = "Generation was interrupted. Ready questions are saved. Retry unfinished sections."))
            write(book, record)
        }
        return record
    }

    @Synchronized
    fun start(book: Book, accountId: String, connecting: Boolean = false): PracticeRecord {
        requireCurrent(book)
        val previous = load(book)
        if (previous.working) return previous
        val sections = book.units.associate { unit ->
            val ready = previous.questions.any { book.units[it.unitIndex].id == unit.id }
            unit.id to if (ready) SectionQuestionStatus.READY
                else if (previous.task?.sections?.get(unit.id) == SectionQuestionStatus.INSUFFICIENT) SectionQuestionStatus.INSUFFICIENT
                else SectionQuestionStatus.PENDING
        }
        val task = QuestionTask(UUID.randomUUID().toString(), book.documentVersion, accountId,
            if (connecting) QuestionTaskStatus.CONNECTING else QuestionTaskStatus.RUNNING, sections)
        return previous.copy(task = task, revision = previous.revision + 1).also { write(book, it) }
    }

    @Synchronized
    fun activate(book: Book, taskId: String, accountId: String): PracticeRecord? = changeTask(book, taskId) {
        it.copy(status = QuestionTaskStatus.RUNNING, accountId = accountId, message = "")
    }

    @Synchronized
    fun sectionReady(book: Book, taskId: String, accountId: String, unit: Int, questions: List<RecallQuestion>): PracticeRecord? {
        requireCurrent(book)
        val previous = load(book)
        val task = previous.task ?: return null
        if (task.id != taskId || task.accountId != accountId || task.status != QuestionTaskStatus.RUNNING || taskId in canceledTasks) return null
        require(questions.size <= 3 && questions.all { it.unitIndex == unit }) { "Invalid section question set." }
        // Validate again at the publication boundary, including locally derived source pages.
        questions.forEach { q ->
            val parsed = QuestionValidator.parse(JSONObject().put("sectionId", book.units[unit].id)
                .put("questions", JSONArray().put(content(q))).toString(), book, unit,
                QuestionSource(book.units[unit].id, book.units[unit].sourceSpans, 3)).single()
            require(parsed == q) { "Invalid question publication." }
        }
        val all = previous.questions.filterNot { it.unitIndex == unit } + questions
        val keys = all.map(::answerKey).toSet()
        val record = previous.copy(questions = all.sortedBy { it.unitIndex }, revision = previous.revision + 1,
            answers = previous.answers.filterKeys { it in keys }, task = task.copy(sections = task.sections +
                (book.units[unit].id to if (questions.isEmpty()) SectionQuestionStatus.INSUFFICIENT else SectionQuestionStatus.READY)))
        return record.also { write(book, it) }
    }

    @Synchronized
    fun changeTask(book: Book, taskId: String, change: (QuestionTask) -> QuestionTask): PracticeRecord? {
        if (books.findBook(book.id)?.documentVersion != book.documentVersion) return null
        val previous = load(book)
        val task = previous.task ?: return null
        if (task.id != taskId || !previous.working) return null
        return previous.copy(task = change(task), revision = previous.revision + 1).also { write(book, it) }
    }

    @Synchronized
    fun answer(book: Book, question: RecallQuestion, outcome: Outcome): PracticeRecord {
        requireCurrent(book)
        val previous = load(book)
        require(previous.questions.any { it.id == question.id && it.version == question.version }) { "This question was replaced. Open practice again." }
        return previous.copy(answers = previous.answers + (answerKey(question) to outcome), revision = previous.revision + 1).also { write(book, it) }
    }

    @Synchronized
    fun delete(bookId: String) { AtomicFile(file(bookId)).delete() }

    @Synchronized
    fun resetAnswers(book: Book): PracticeRecord {
        val previous = load(book)
        return previous.copy(answers = emptyMap(), revision = previous.revision + 1).also { write(book, it) }
    }

    fun storedBytes(): Long = directory.walkTopDown().filter(File::isFile).sumOf(File::length)

    private fun requireCurrent(book: Book) {
        require(books.findBook(book.id)?.documentVersion == book.documentVersion) { "This book was removed or changed." }
    }

    private fun file(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid book identifier." }
        return File(directory, "$id.json")
    }

    private fun content(q: RecallQuestion): JSONObject = JSONObject().put("prompt", q.prompt)
        .put("options", JSONArray(q.options)).put("correctIndex", q.correctIndex).put("explanation", q.explanation)
        .put("sourcePage", q.sourcePage).put("sourceExcerpt", q.sourceExcerpt)

    private fun write(book: Book, record: PracticeRecord) {
        val task = record.task?.let { t -> JSONObject().put("id", t.id).put("accountId", t.accountId)
            .put("status", t.status.name).put("message", t.message)
            .put("sections", JSONObject(t.sections.mapValues { it.value.name })) }
        val json = JSONObject().put("schema", 1).put("bookId", book.id).put("documentVersion", record.documentVersion).put("revision", record.revision)
            .put("questions", JSONArray(record.questions.map { q -> JSONObject().put("unitIndex", q.unitIndex)
                .put("id", q.id).put("version", q.version).put("content", content(q)) }))
            .put("answers", JSONObject(record.answers.mapValues { it.value.name })).put("task", task ?: JSONObject.NULL)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8 * 1024 * 1024) { "Practice storage is too large." }
        val atomic = AtomicFile(file(book.id))
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            requireCurrent(book)
            require(record.task?.status != QuestionTaskStatus.RUNNING || record.task.id !in canceledTasks) { "Generation was canceled." }
            atomic.finishWrite(output)
        }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
}
