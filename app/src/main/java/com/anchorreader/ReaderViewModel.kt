package com.anchorreader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anchorreader.data.Book
import com.anchorreader.data.BookRepository
import com.anchorreader.data.Outcome
import com.anchorreader.data.Progress
import com.anchorreader.data.RecallQuestion
import com.anchorreader.data.SampleLesson
import com.anchorreader.data.PracticeRecord
import com.anchorreader.data.PracticeRepository
import com.anchorreader.data.QuestionTaskStatus
import com.anchorreader.data.SectionQuestionStatus
import com.anchorreader.data.QuestionSources
import com.anchorreader.data.QuestionValidator
import com.anchorreader.chatgpt.ChatGptProvider
import com.anchorreader.chatgpt.ChatGptAccountState
import com.anchorreader.chatgpt.ProviderException
import com.anchorreader.chatgpt.ProviderFailureKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Screen { LIBRARY, NEW, OVERVIEW, PLAYER, CHECK, FINISH, SETTINGS }

data class ReaderState(
    val books: List<Book> = emptyList(),
    val progress: Map<String, Progress> = emptyMap(),
    val screen: Screen = Screen.LIBRARY,
    val activeId: String = SampleLesson.book.id,
    val loading: Boolean = true,
    val error: String? = null,
    val selectedAnswer: Int? = null,
    val outcome: Outcome? = null,
    val questionIndex: Int = 0,
    val stillMode: Boolean = false,
    val storedBytes: Long = 0,
    val playbackRequest: Long = 0,
    val autoplayRequested: Boolean = false,
    val practice: Map<String, PracticeRecord> = emptyMap(),
    val practiceSession: List<RecallQuestion> = emptyList(),
    val practiceReturnScreen: Screen = Screen.OVERVIEW,
    val account: ChatGptAccountState = ChatGptAccountState(),
    val browserUrl: String? = null,
    val confirmPlanUse: Boolean = false,
) {
    val book: Book get() = books.find { it.id == activeId } ?: SampleLesson.book
    val currentProgress: Progress get() = progress[activeId] ?: Progress()
    val currentPractice: PracticeRecord get() = practice[activeId] ?: PracticeRecord(book.documentVersion)
    val savedQuestions: List<RecallQuestion> get() = if (book.isSample) SampleLesson.questions else currentPractice.questions
    val questions: List<RecallQuestion> get() = if (screen in setOf(Screen.CHECK, Screen.FINISH) && practiceSession.isNotEmpty()) practiceSession else savedQuestions
    val resultAnswers: Map<Int, Outcome> get() = questions.mapIndexedNotNull { index, question ->
        val outcome = if (book.isSample) currentProgress.answers[SampleLesson.questions.indexOf(question)] else currentPractice.answer(question)
        outcome?.let { index to it }
    }.toMap()
}

class ReaderViewModel internal constructor(application: Application, private val provider: ChatGptProvider,
    private val answerWriteDispatcher: CoroutineDispatcher) : AndroidViewModel(application) {
    @JvmOverloads
    constructor(application: Application, provider: ChatGptProvider = ChatGptProvider(application)) :
        this(application, provider, Dispatchers.Main.immediate)
    private val repository = BookRepository(application)
    private val practiceRepository = PracticeRepository(application, repository)
    private val settings = application.getSharedPreferences("preferences", 0)
    private val writeLock = Mutex()
    private val mutableState = MutableStateFlow(ReaderState(stillMode = settings.getBoolean("still", false)))
    val state = mutableState.asStateFlow()
    private var previousScreen = Screen.LIBRARY
    private var storageEpoch = 0L
    private val bookVersions = mutableMapOf<String, Long>()
    private var generationJob: Job? = null
    private var generationBookId: String? = null
    private var generationTaskId: String? = null

    init {
        reload()
        viewModelScope.launch { provider.state.collect { account ->
            update { it.copy(account = account) }
            if (generationJob?.isActive == true && generationTaskId != null) {
                val task = generationBookId?.let { state.value.practice[it]?.task }
                if (task?.status == QuestionTaskStatus.RUNNING && task.accountId != account.accountId) cancelQuestions()
            }
        } }
    }

    private fun update(block: (ReaderState) -> ReaderState) { mutableState.value = block(mutableState.value) }

    private fun reload() {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val books = repository.loadBooks()
                    books to books.associate { it.id to repository.loadProgress(it.id) }
                }
                val practice = withContext(Dispatchers.IO) { loaded.first.filterNot { it.isSample }.associate { book ->
                    book.id to runCatching { practiceRepository.load(book, recoverInterrupted = true) }.getOrElse { PracticeRecord(book.documentVersion) }
                } }
                val pendingBook = settings.getString("question-book", null)
                settings.edit().remove("question-book").commit()
                val bytes = withContext(Dispatchers.IO) { totalStoredBytes() }
                update { it.copy(books = loaded.first, progress = loaded.second, practice = practice, storedBytes = bytes,
                    activeId = pendingBook?.takeIf { id -> loaded.first.any { book -> book.id == id } } ?: it.activeId,
                    screen = if (pendingBook != null && loaded.first.any { book -> book.id == pendingBook }) Screen.OVERVIEW else it.screen,
                    loading = false, error = repository.storageWarnings.takeIf { warnings -> warnings.isNotEmpty() }?.joinToString("\n")) }
            } catch (error: Exception) { fail(error) }
        }
    }

    private fun fail(error: Exception) {
        update { it.copy(loading = false, error = error.message ?: "Could not save this change. Try again.") }
    }

    fun clearError() = update { it.copy(error = null) }
    fun navigate(screen: Screen) { if (!state.value.loading) update { it.copy(screen = screen, error = null, autoplayRequested = false) } }
    fun details(book: Book) { if (!state.value.loading) update { it.copy(activeId = book.id, screen = Screen.OVERVIEW) } }
    fun openSettings() {
        if (state.value.loading || state.value.screen == Screen.SETTINGS) return
        previousScreen = state.value.screen
        navigate(Screen.SETTINGS)
    }
    fun closeSettings() = navigate(previousScreen)
    fun setStill(value: Boolean) {
        update { it.copy(stillMode = value) }
        settings.edit().putBoolean("still", value).apply()
    }

    private fun totalStoredBytes(): Long = repository.storedBytes() + practiceRepository.storedBytes()

    private fun persist(progress: Progress) {
        if (state.value.loading) return
        val id = state.value.activeId
        val epoch = storageEpoch
        val version = bookVersions[id] ?: 0L
        update { it.copy(progress = it.progress + (id to progress)) }
        viewModelScope.launch {
            try {
                val bytes = writeLock.withLock {
                    if (epoch != storageEpoch || version != (bookVersions[id] ?: 0L)) return@launch
                    withContext(Dispatchers.IO) { repository.saveProgress(id, progress); totalStoredBytes() }
                }
                update { it.copy(storedBytes = bytes) }
            } catch (error: Exception) { fail(error) }
        }
    }

    fun start(book: Book, unit: Int? = null) {
        if (state.value.loading) return
        update { it.copy(activeId = book.id) }
        val previous = state.value.currentProgress
        val index = (unit ?: if (previous.completed) 0 else previous.unitIndex).coerceIn(book.units.indices)
        val sentence = if (unit != null || previous.completed) 0 else previous.sentenceIndex.coerceIn(book.units[index].sentences.indices)
        persist(previous.copy(unitIndex = index, sentenceIndex = sentence, visited = previous.visited + index))
        update { it.copy(screen = Screen.PLAYER, error = null, practiceSession = emptyList(), playbackRequest = it.playbackRequest + 1, autoplayRequested = true) }
    }

    fun cancelPlaybackRequest() = update { it.copy(autoplayRequested = false) }

    fun consumePlaybackRequest(request: Long): Boolean {
        val current = state.value
        if (!current.autoplayRequested || current.playbackRequest != request || current.screen != Screen.PLAYER) return false
        cancelPlaybackRequest()
        return true
    }

    fun setSentence(index: Int) {
        val current = state.value.currentProgress
        persist(current.copy(sentenceIndex = index.coerceIn(state.value.book.units[current.unitIndex].sentences.indices)))
    }

    fun nextSentence(): Boolean {
        val current = state.value.currentProgress
        return if (current.sentenceIndex + 1 < state.value.book.units[current.unitIndex].sentences.size) {
            setSentence(current.sentenceIndex + 1)
            true
        } else false
    }

    fun previousConcept() {
        val current = state.value.currentProgress
        if (current.unitIndex > 0) start(state.value.book, current.unitIndex - 1)
    }

    fun nextConcept() = advanceUnit()

    fun selectConcept(index: Int) {
        val current = state.value
        if (current.screen == Screen.PLAYER && index in current.book.units.indices && index != current.currentProgress.unitIndex) start(current.book, index)
    }

    fun openRecall() {
        if (!state.value.book.isSample) { openPractice(state.value.currentProgress.unitIndex); return }
        val current = state.value
        val index = SampleLesson.questions.indexOfFirst { it.unitIndex == current.currentProgress.unitIndex }
        if (index >= 0) update { it.copy(screen = Screen.CHECK, practiceReturnScreen = current.screen,
            practiceSession = SampleLesson.questions.drop(index), questionIndex = 0,
            selectedAnswer = null, outcome = null, autoplayRequested = false) }
    }

    private fun advanceUnit() {
        val current = state.value.currentProgress
        if (current.unitIndex + 1 < state.value.book.units.size) start(state.value.book, current.unitIndex + 1)
        else {
            persist(current.copy(completed = true))
            update { it.copy(screen = Screen.FINISH, practiceSession = emptyList(), autoplayRequested = false) }
        }
    }

    fun selectAnswer(index: Int) = update { it.copy(selectedAnswer = index) }
    fun answer(skip: Boolean = false) {
        val current = state.value
        if (current.outcome != null || (!skip && current.selectedAnswer == null)) return
        val question = current.questions[current.questionIndex]
        val outcome = if (skip) Outcome.SKIPPED else if (current.selectedAnswer == question.correctIndex) Outcome.CORRECT else Outcome.WRONG
        if (current.book.isSample) persist(current.currentProgress.copy(answers = current.currentProgress.answers + (SampleLesson.questions.indexOf(question) to outcome)))
        else {
            val epoch = storageEpoch
            val version = bookVersions[current.book.id] ?: 0L
            viewModelScope.launch(answerWriteDispatcher) {
                withContext(Dispatchers.Main.immediate) {
                    try {
                        val record = writeLock.withLock {
                            if (!currentAnswer(current.book, question, epoch, version)) return@withContext
                            withContext(Dispatchers.IO) { practiceRepository.answer(current.book, question, outcome) }
                        }
                        if (currentAnswer(current.book, question, epoch, version)) publishPractice(current.book, record)
                    } catch (error: Exception) {
                        if (currentAnswer(current.book, question, epoch, version)) fail(error)
                    }
                }
            }
        }
        update { it.copy(outcome = outcome) }
    }

    private fun currentAnswer(book: Book, question: RecallQuestion, epoch: Long, version: Long): Boolean =
        epoch == storageEpoch && version == (bookVersions[book.id] ?: 0L) &&
            state.value.books.any { it.id == book.id && it.documentVersion == book.documentVersion } &&
            state.value.practice[book.id]?.questions?.any { it.id == question.id && it.version == question.version } == true

    fun continueAfterQuestion() {
        if (state.value.outcome == null) return
        if (state.value.questionIndex + 1 < state.value.questions.size) update { it.copy(questionIndex = it.questionIndex + 1, selectedAnswer = null, outcome = null) }
        else update { it.copy(screen = Screen.FINISH, autoplayRequested = false) }
    }

    fun reviewConcept() = closePractice()

    fun openPractice(unit: Int? = null) {
        val current = state.value
        if (current.loading || current.book.isSample) return
        val questions = current.currentPractice.questions.filter { unit == null || it.unitIndex == unit }
        if (questions.isEmpty()) return
        update { it.copy(screen = Screen.CHECK, practiceReturnScreen = current.screen,
            practiceSession = questions, questionIndex = 0, selectedAnswer = null, outcome = null, autoplayRequested = false) }
    }

    fun closePractice() = update { it.copy(screen = it.practiceReturnScreen, autoplayRequested = false, practiceSession = emptyList(), selectedAnswer = null, outcome = null) }
    fun retryQuestion() = update { it.copy(selectedAnswer = null, outcome = null) }

    fun consumeBrowserUrl() = update { it.copy(browserUrl = null) }

    fun connectChatGpt() {
        if (provider.state.value.signingIn || generationJob?.isActive == true || state.value.practice.values.any { it.working }) return
        generationJob = viewModelScope.launch {
            try {
                val accountId = provider.signIn { url -> update { it.copy(browserUrl = url, autoplayRequested = false) } }
                if (!settings.getBoolean("plan-confirmed-$accountId", false) && provider.state.value.planEnabled) update { it.copy(confirmPlanUse = true) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update { it.copy(error = error.message ?: "ChatGPT was not connected.") } }
        }
    }

    fun signOutChatGpt() {
        cancelQuestions()
        viewModelScope.launch {
            try { provider.signOut(); update { it.copy(confirmPlanUse = false) } }
            catch (_: Exception) { update { it.copy(error = "ChatGPT sign-out could not finish. Try again.") } }
        }
    }

    fun resetChatGptConnection() {
        cancelQuestions()
        viewModelScope.launch {
            try { provider.resetConnection(); update { it.copy(confirmPlanUse = false, error = null) } }
            catch (_: Exception) { update { it.copy(error = "The ChatGPT connection could not be reset. Try again.") } }
        }
    }

    fun confirmChatGptPlan(accepted: Boolean) {
        update { it.copy(confirmPlanUse = false) }
        val id = provider.state.value.accountId
        if (!accepted || id == null) { cancelQuestions(); return }
        settings.edit().putBoolean("plan-confirmed-$id", true).commit()
        val book = state.value.books.firstOrNull { it.id == generationBookId }
        val task = book?.let { state.value.practice[it.id]?.task }
        if (book != null && task?.status == QuestionTaskStatus.CONNECTING) beginGeneration(book, task.id, id)
    }

    fun addQuestions() {
        val book = state.value.book
        if (book.isSample || book.pdfPath == null || state.value.loading) return
        if (generationJob?.isActive == true || state.value.practice.values.any { it.working }) {
            update { it.copy(error = "A question task is already active. Keep reading or cancel it first.") }; return
        }
        generationBookId = book.id
        generationTaskId = null
        settings.edit().putString("question-book", book.id).commit()
        generationJob = viewModelScope.launch {
            try {
                require(java.io.File(book.pdfPath).isFile && book.units.all { it.sourceSpans.isNotEmpty() }) { "The source pages are unavailable. Restore the original PDF before adding questions." }
                val account = provider.state.value
                val connecting = !account.connected || !account.planEnabled || !settings.getBoolean("plan-confirmed-${account.accountId}", false)
                val record = withContext(Dispatchers.IO) { practiceRepository.start(book, account.accountId ?: "pending", connecting) }
                generationTaskId = record.task!!.id
                publishPractice(book, record)
                val id = if (!account.connected) provider.signIn { url -> update { it.copy(browserUrl = url, autoplayRequested = false) } } else account.accountId!!
                if (!provider.state.value.planEnabled) throw IllegalStateException("ChatGPT is connected, but this account has not granted plan use. Check your plan and reconnect.")
                if (!settings.getBoolean("plan-confirmed-$id", false)) update { it.copy(confirmPlanUse = true) }
                else runGeneration(book, record.task.id, id)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failGeneration(book, error) }
        }
    }

    private fun beginGeneration(book: Book, taskId: String, accountId: String) {
        generationJob = viewModelScope.launch {
            try { runGeneration(book, taskId, accountId) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { failGeneration(book, error) }
        }
    }

    private suspend fun runGeneration(book: Book, taskId: String, accountId: String) {
        val activated = withContext(Dispatchers.IO) { practiceRepository.activate(book, taskId, accountId) } ?: return
        publishPractice(book, activated)
        book.units.forEachIndexed { unitIndex, unit ->
            if (activated.task!!.sections[unit.id] in setOf(SectionQuestionStatus.READY, SectionQuestionStatus.INSUFFICIENT)) return@forEachIndexed
            val candidates = mutableListOf<RecallQuestion>()
            for (source in QuestionSources.chunks(unit)) {
                require(provider.state.value.accountId == accountId && generationTaskId == taskId) { "The ChatGPT account changed. Retry with the selected account." }
                val json = provider.response(accountId, QuestionValidator.INSTRUCTIONS + " Make at most ${source.maxQuestions} questions.", source.content, QuestionValidator.schema(source.maxQuestions))
                candidates += QuestionValidator.parse(json, book, unitIndex, source)
            }
            require(provider.state.value.accountId == accountId && generationTaskId == taskId) { "The ChatGPT account changed. Ready questions are saved." }
            val saved = withContext(Dispatchers.IO) { practiceRepository.sectionReady(book, taskId, accountId, unitIndex, QuestionSources.selectQuestions(candidates)) } ?: return
            publishPractice(book, saved)
        }
        val completed = withContext(Dispatchers.IO) { practiceRepository.changeTask(book, taskId) { it.copy(status = QuestionTaskStatus.COMPLETE, message = "Questions are ready. Saved questions work offline.") } }
        if (completed != null) publishPractice(book, completed)
        settings.edit().remove("question-book").commit()
    }

    private suspend fun failGeneration(book: Book, error: Exception) {
        val message = if (error is org.json.JSONException) "ChatGPT returned invalid question data. Your book and ready questions are saved."
            else error.message ?: "Questions could not be added. Your book and ready questions are saved."
        val taskId = generationTaskId ?: return update { it.copy(error = message) }
        val status = if (error is ProviderException && error.kind == ProviderFailureKind.LIMIT) QuestionTaskStatus.LIMIT else QuestionTaskStatus.ERROR
        val record = withContext(Dispatchers.IO) { runCatching { practiceRepository.changeTask(book, taskId) { it.copy(status = status, message = message) } }.getOrNull() }
        if (record != null) publishPractice(book, record)
        settings.edit().remove("question-book").commit()
    }

    fun cancelQuestions() {
        val book = state.value.books.firstOrNull { it.id == generationBookId }
        val taskId = generationTaskId
        if (taskId != null) practiceRepository.fenceTask(taskId)
        generationTaskId = null
        if (provider.state.value.signingIn) provider.cancelSignIn()
        provider.cancelRequests(); generationJob?.cancel(); generationJob = null
        settings.edit().remove("question-book").commit()
        update { it.copy(browserUrl = null, confirmPlanUse = false) }
        if (book != null && taskId != null) viewModelScope.launch {
            val record = withContext(Dispatchers.IO) { runCatching { practiceRepository.changeTask(book, taskId) { it.copy(status = QuestionTaskStatus.CANCELED,
                message = "Generation stopped. Your book and ready questions are saved.") } }.getOrNull() }
            if (record != null) publishPractice(book, record)
        }
    }

    private fun publishPractice(book: Book, record: PracticeRecord) {
        if (state.value.books.none { it.id == book.id && it.documentVersion == record.documentVersion } ||
            (state.value.practice[book.id]?.revision ?: -1) > record.revision) return
        update { it.copy(practice = it.practice + (book.id to record)) }
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { totalStoredBytes() }
            update { it.copy(storedBytes = bytes) }
        }
    }

    fun saveText(title: String, author: String, text: String) = saveBook { repository.saveText(title, author, text) }
    fun importPdf(uri: Uri, title: String, author: String, questions: Boolean = false) = saveBook(questions) { repository.importPdf(uri, title, author) }

    private fun saveBook(questions: Boolean = false, action: () -> Book) {
        if (state.value.loading) return
        update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val book = writeLock.withLock { withContext(Dispatchers.IO) { action() } }
                val bytes = withContext(Dispatchers.IO) { totalStoredBytes() }
                update { it.copy(books = listOf(book) + it.books.filter { saved -> saved.id != book.id }, progress = it.progress + (book.id to Progress()), activeId = book.id,
                    screen = if (book.pdfPath != null) Screen.OVERVIEW else Screen.LIBRARY, loading = false, storedBytes = bytes) }
                if (questions) addQuestions()
            } catch (error: Exception) { fail(error) }
        }
    }

    fun deleteBook() {
        val book = state.value.book
        if (book.isSample || state.value.loading) return
        if (generationBookId == book.id) cancelQuestions()
        bookVersions[book.id] = (bookVersions[book.id] ?: 0L) + 1L
        update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                writeLock.withLock { withContext(Dispatchers.IO) { repository.delete(book); practiceRepository.delete(book.id) } }
                val bytes = withContext(Dispatchers.IO) { totalStoredBytes() }
                update { it.copy(books = it.books.filter { saved -> saved.id != book.id }, progress = it.progress - book.id, practice = it.practice - book.id, activeId = SampleLesson.book.id, screen = Screen.LIBRARY, storedBytes = bytes, loading = false) }
            } catch (error: Exception) { fail(error) }
        }
    }

    fun resetProgress() {
        if (state.value.loading) return
        storageEpoch++
        update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val bytes = writeLock.withLock { withContext(Dispatchers.IO) {
                    repository.resetAllProgress()
                    state.value.books.filterNot { it.isSample }.forEach { practiceRepository.resetAnswers(it) }
                    totalStoredBytes()
                } }
                update { it.copy(progress = it.books.associate { book -> book.id to Progress() },
                    practice = it.practice.mapValues { entry -> entry.value.copy(answers = emptyMap()) }, storedBytes = bytes, loading = false) }
                previousScreen = Screen.LIBRARY
                closeSettings()
            } catch (error: Exception) { fail(error) }
        }
    }

    override fun onCleared() { provider.cancelSignIn(); provider.cancelRequests(); super.onCleared() }
}
