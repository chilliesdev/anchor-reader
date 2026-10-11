package com.anchorreader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anchorreader.ui.*

class MainActivity : ComponentActivity() {
    private lateinit var narrator: Narrator
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { narrator.pause() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        narrator = Narrator(this)
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { AnchorTheme { ReaderApp(narrator) } }
    }

    override fun onResume() { super.onResume(); if (::narrator.isInitialized) narrator.refresh(this) }
    override fun onStop() { narrator.pause(); super.onStop() }
    override fun onDestroy() { unregisterReceiver(noisy); narrator.close(); super.onDestroy() }
}

private fun openVoiceSettings(context: Context) {
    try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
    catch (_: android.content.ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
}

@Composable
fun ReaderApp(narrator: Narration, model: ReaderViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val voice by narrator.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    var foreground by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    fun pause() { model.cancelPlaybackRequest(); narrator.pause() }
    DisposableEffect(owner, narrator, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) foreground = true
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) { foreground = false; pause() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); pause() }
    }
    DisposableEffect(state.screen, dialog) {
        val activity = context as? android.app.Activity
        if (activity != null) {
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            // Floating dialogs dim the underlying screen behind the system bars.
            val light = state.screen != Screen.PLAYER
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
        onDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.screen, dialog) { if (state.screen != Screen.PLAYER || dialog != null) pause() }
    LaunchedEffect(Unit) {
        val preferences = context.getSharedPreferences("preferences", 0)
        if (!preferences.contains("still") && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) model.setStill(true)
    }
    LaunchedEffect(state.error) {
        if (state.error != null && state.screen != Screen.NEW) {
            snackbar.showSnackbar(state.error!!)
            model.clearError()
        }
    }
    LaunchedEffect(state.browserUrl) {
        state.browserUrl?.let { url ->
            model.consumeBrowserUrl()
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: android.content.ActivityNotFoundException) { model.cancelQuestions() }
        }
    }
    fun manageUsage() {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) }
    }
    fun speakCurrent() {
        val current = model.state.value
        val progress = current.currentProgress
        val text = current.book.units[progress.unitIndex].sentences[progress.sentenceIndex]
        narrator.speak(text) {
            val latest = model.state.value
            if (foreground && dialog == null && latest.screen == Screen.PLAYER && latest.activeId == current.activeId &&
                latest.playbackRequest == current.playbackRequest && latest.currentProgress.unitIndex == progress.unitIndex && latest.currentProgress.sentenceIndex == progress.sentenceIndex && model.nextSentence()) speakCurrent()
        }
    }
    LaunchedEffect(state.playbackRequest, state.autoplayRequested, voice.ready, voice.initializing, foreground, dialog) {
        if (state.autoplayRequested && !voice.initializing && !voice.ready) model.cancelPlaybackRequest()
        if (state.autoplayRequested && voice.ready && foreground && dialog == null && model.consumePlaybackRequest(state.playbackRequest)) speakCurrent()
    }
    fun pauseAnd(action: () -> Unit) { pause(); action() }
    fun back() {
        pauseAnd {
            if (dialog != null) dialog = null
            else when (state.screen) {
                Screen.SETTINGS -> model.closeSettings()
                Screen.CHECK -> if (state.book.isSample) model.navigate(Screen.OVERVIEW) else model.closePractice()
                Screen.PLAYER, Screen.FINISH -> model.navigate(Screen.OVERVIEW)
                else -> model.navigate(Screen.LIBRARY)
            }
        }
    }
    BackHandler(enabled = state.screen != Screen.LIBRARY || dialog != null) { back() }
    if (state.screen == Screen.PLAYER) {
        Box(Modifier.fillMaxSize()) {
          PlayerScreen(
            state.book, state.currentProgress, voice, state.stillMode, state.autoplayRequested,
            onBack = { back() }, onTranscript = { pauseAnd { dialog = "transcript" } },
            onPlay = { if (voice.playing || state.autoplayRequested) pause() else { model.cancelPlaybackRequest(); speakCurrent() } },
            onReplay = { model.cancelPlaybackRequest(); speakCurrent() }, onSpeed = { model.cancelPlaybackRequest(); narrator.changeSpeed() },
            onPrevious = { pauseAnd { model.previousConcept() } },
            onNext = { pauseAnd { model.nextConcept() } },
            onConceptSettled = { index -> pauseAnd { model.selectConcept(index) } },
            onCheck = if (state.questions.any { it.unitIndex == state.currentProgress.unitIndex }) ({ pauseAnd { model.openRecall() } }) else null,
            onStill = { model.setStill(!state.stillMode) },
            onSource = { pauseAnd { dialog = "source" } },
            onVoiceSettings = { openVoiceSettings(context) },
            onPreviousSentence = { pauseAnd { model.setSentence(state.currentProgress.sentenceIndex - 1) } },
            onNextSentence = { pauseAnd { model.nextSentence() } },
          )
          SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp))
        }
    } else {
        Scaffold(
            containerColor = Palette.Canvas,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { Column(Modifier.statusBarsPadding()) { AppHeader(onHome = { model.navigate(Screen.LIBRARY) }, onSettings = { model.openSettings() }) } },
            bottomBar = {
                if (state.screen in listOf(Screen.LIBRARY, Screen.NEW, Screen.OVERVIEW)) {
                    Column(Modifier.navigationBarsPadding().imePadding()) { AppTabs(state.screen == Screen.NEW, { model.navigate(Screen.NEW) }, { model.navigate(Screen.LIBRARY) }) }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (state.loading && state.books.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else when (state.screen) {
                    Screen.LIBRARY -> LibraryScreen(state.books, state.progress, { pauseAnd { model.start(it) } }, { pauseAnd { model.details(it) } }, state.practice)
                    Screen.NEW -> NewBookScreen(model::saveText, model::importPdf, state.loading, state.error, { model.navigate(Screen.LIBRARY) })
                    Screen.OVERVIEW -> OverviewScreen(state.book, state.currentProgress, { model.navigate(Screen.LIBRARY) }, { pauseAnd { model.start(state.book, it) } }, { dialog = "source" }, { dialog = "transcript" }, model::deleteBook,
                        state.currentPractice, model::addQuestions, model::cancelQuestions, { unit -> pauseAnd { model.openPractice(unit) } }, ::manageUsage)
                    Screen.CHECK -> CheckScreen(state.questions[state.questionIndex], state.questionIndex + 1, state.outcome, state.selectedAnswer, model::selectAnswer, { model.answer() }, { model.answer(true) }, model::continueAfterQuestion, model::reviewConcept, { dialog = "source" },
                        total = if (state.book.isSample) 0 else state.questions.size,
                        sectionTitle = if (state.book.isSample) "" else state.book.units[state.questions[state.questionIndex].unitIndex].title,
                        onRetry = model::retryQuestion)
                    Screen.FINISH -> {
                        val summary = if (state.book.isSample) state.currentProgress else state.currentProgress.copy(answers = state.questions.mapIndexedNotNull { index, q -> state.currentPractice.answer(q)?.let { index to it } }.toMap())
                        FinishScreen(state.book, summary, state.questions, { model.start(state.book, 0) }, { model.navigate(Screen.LIBRARY) }, { dialog = "source" })
                    }
                    Screen.SETTINGS -> SettingsScreen(state.stillMode, model::setStill, state.storedBytes, voice.status, { openVoiceSettings(context) }, model::resetProgress, model::closeSettings, onLicenses = { dialog = "licenses" },
                        account = state.account, onConnect = model::connectChatGpt, onSignOut = model::signOutChatGpt,
                        onCancelSignIn = model::cancelQuestions, onManageUsage = ::manageUsage, onResetConnection = model::resetChatGptConnection)
                    Screen.PLAYER -> Unit
                }
            }
        }
    }
    when (dialog) {
        "licenses" -> LegalDialog { dialog = null }
        "transcript" -> TranscriptDialog(state.book) { dialog = null }
        "source" -> SourceDialog(state.book, ((if (state.screen == Screen.CHECK) state.questions.getOrNull(state.questionIndex)?.sourcePage else null)
            ?: state.book.units.getOrNull(state.currentProgress.unitIndex)?.sourcePage ?: 1) - 1) { dialog = null }
    }
    if (state.confirmPlanUse) AlertDialog(onDismissRequest = { model.confirmChatGptPlan(false) }, title = { Text("Use your ChatGPT plan?") },
        text = { Text("Practice questions use your connected ChatGPT plan and its usage limits. Reading and saved questions remain available offline. You can manage usage in ChatGPT.") },
        confirmButton = { TextButton({ model.confirmChatGptPlan(true) }) { Text("Use ChatGPT plan") } },
        dismissButton = { TextButton({ model.confirmChatGptPlan(false) }) { Text("Keep reading") } })
}
