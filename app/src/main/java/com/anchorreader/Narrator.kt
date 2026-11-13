package com.anchorreader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.anchorreader.supertonic.SupertonicEngine
import com.anchorreader.supertonic.SupertonicManifest
import com.anchorreader.supertonic.SupertonicQualityMode
import com.anchorreader.supertonic.SupertonicQualityStore
import com.anchorreader.supertonic.VoicePackService
import com.anchorreader.supertonic.VoicePackState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.util.Locale

data class VoiceState(
    val ready: Boolean = false,
    val initializing: Boolean = true,
    val playing: Boolean = false,
    val status: String = "Starting device voice…",
    val speed: Float = 1f,
    val packState: VoicePackState = VoicePackState.NotInstalled,
    val useHd: Boolean = true,
    val selectedVoice: String = "F1",
    val qualityMode: SupertonicQualityMode = SupertonicQualityMode.STUDIO_5_STEP,
)

interface Narration {
    val state: StateFlow<VoiceState>
    fun speak(text: String, onDone: () -> Unit)
    fun pause()
    fun changeSpeed(): Float
    fun prepareNext(text: String) {}
    fun setEnginePreference(useHd: Boolean) {}
    fun setVoicePreference(voice: String) {}
    fun setQualityMode(mode: SupertonicQualityMode) {}
    fun downloadVoicePack() {}
    fun cancelVoicePackDownload() {}
    fun deleteVoicePack() {}
    fun previewVoice(onDone: () -> Unit = {}) {}
}

fun Narration.prepareNext(texts: List<String>) {
    if (this is Narrator) {
        this.prepareNextSentences(texts)
    } else {
        texts.firstOrNull()?.let { prepareNext(it) }
    }
}

/**
 * Thread-safe, clause-streaming entry for a single synthesized sentence.
 * Clauses are appended as they finish synthesis; readers collecting [asFlow]
 * receive already-cached clauses immediately (<1ms) and suspend only while
 * awaiting remaining in-flight clauses.
 */
internal class SentenceStreamEntry(
    val key: String,
    val text: String,
    val voice: String,
    val speed: Float,
    val qualityMode: SupertonicQualityMode,
) {
    private val lock = Any()
    private val chunks = ArrayList<ShortArray>()
    private val stateVersion = MutableStateFlow(0)

    @Volatile
    var completed: Boolean = false
        private set

    @Volatile
    var failed: Boolean = false
        private set

    fun setChunk(clauseIndex: Int, chunk: ShortArray) {
        if (chunk.isEmpty()) return
        synchronized(lock) {
            if (completed || failed) return
            if (clauseIndex == chunks.size) {
                chunks.add(chunk)
                stateVersion.value += 1
            } else if (clauseIndex in 0 until chunks.size) {
                chunks[clauseIndex] = chunk
                stateVersion.value += 1
            }
        }
    }

    fun markCompleted() {
        synchronized(lock) {
            completed = true
            stateVersion.value += 1
        }
    }

    fun markFailed() {
        synchronized(lock) {
            failed = true
            stateVersion.value += 1
        }
    }

    fun chunkCount(): Int = synchronized(lock) { chunks.size }

    fun snapshotChunks(): List<ShortArray> = synchronized(lock) { chunks.toList() }

    fun asFlow(): Flow<ShortArray> = flow {
        var index = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            var nextChunk: ShortArray? = null
            var isDone = false
            var observedVersion = 0
            synchronized(lock) {
                observedVersion = stateVersion.value
                if (index < chunks.size) {
                    nextChunk = chunks[index]
                    index++
                } else {
                    isDone = completed || failed
                }
            }
            if (nextChunk != null) {
                emit(nextChunk!!)
            } else if (isDone) {
                break
            } else {
                stateVersion.first { it != observedVersion }
            }
        }
    }
}

/**
 * Priority-ordered, multi-sentence lookahead prefetch pipeline for Supertonic HD TTS.
 *
 * Guarantees:
 * 1. Synchronous claim in [claimForPlayback] before any subsequent [enqueueLookahead] call
 *    can run, preventing `speak(N)` + `prepareNext(N+1)` from ever evicting or cancelling `N` or `N+1`.
 * 2. Strict priority serialization: all clauses of [activePlaybackKey] are synthesized first
 *    before any background lookahead sentence (`N+1`, `N+2`) acquires the synthesis engine.
 * 3. Cache preservation across `pause()`: valid pre-synthesized entries remain warm in the
 *    bounded LRU map until `voice`, `speed`, or `qualityMode` changes or the engine is closed.
 */
internal class HdPrefetchPipeline(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val maxEntries: Int = 4,
    private val synthesizeStreamFn: (text: String, voice: String, speed: Float, qualityMode: SupertonicQualityMode) -> Flow<ShortArray>,
) {
    private val lock = Any()
    private val cache = LinkedHashMap<String, SentenceStreamEntry>(maxEntries + 2, 0.75f, true)
    private val lookaheadQueue = ArrayList<String>()
    private var activePlaybackKey: String? = null
    private var currentSynthesizingKey: String? = null
    private var currentEntryJob: Job? = null
    private var workerJob: Job? = null
    private val wakeup = MutableStateFlow(0)

    companion object {
        fun buildCacheKey(voice: String, speed: Float, qualityMode: SupertonicQualityMode, text: String): String =
            "$voice:$speed:${qualityMode.name}:${text.trim()}"
    }

    fun claimForPlayback(
        text: String,
        voice: String,
        speed: Float,
        qualityMode: SupertonicQualityMode,
    ): SentenceStreamEntry {
        val trimmed = text.trim()
        val key = buildCacheKey(voice, speed, qualityMode, trimmed)
        val entry: SentenceStreamEntry
        var jobToCancel: Job? = null
        synchronized(lock) {
            val existing = cache[key]
            if (existing != null && !existing.failed) {
                entry = existing
            } else {
                entry = SentenceStreamEntry(key, trimmed, voice, speed, qualityMode)
                cache[key] = entry
            }
            activePlaybackKey = key
            lookaheadQueue.remove(key)
            if (!entry.completed && currentSynthesizingKey != null && currentSynthesizingKey != key) {
                jobToCancel = currentEntryJob
            }
            trimCacheLocked()
            wakeup.value += 1
            ensureWorkerLocked()
        }
        jobToCancel?.cancel()
        return entry
    }

    fun enqueueLookahead(
        texts: List<String>,
        voice: String,
        speed: Float,
        qualityMode: SupertonicQualityMode,
    ) {
        val cleanTexts = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanTexts.isEmpty()) return
        synchronized(lock) {
            lookaheadQueue.clear()
            for (text in cleanTexts) {
                val key = buildCacheKey(voice, speed, qualityMode, text)
                val existing = cache[key]
                if (existing == null || existing.failed) {
                    cache[key] = SentenceStreamEntry(key, text, voice, speed, qualityMode)
                }
                if (key != activePlaybackKey) {
                    lookaheadQueue.add(key)
                }
            }
            trimCacheLocked()
            wakeup.value += 1
            ensureWorkerLocked()
        }
    }

    fun clearActivePlayback(keepInLookahead: Boolean = true) {
        synchronized(lock) {
            val prevActive = activePlaybackKey
            activePlaybackKey = null
            if (keepInLookahead && prevActive != null) {
                val entry = cache[prevActive]
                if (entry != null && !entry.completed && !entry.failed && prevActive !in lookaheadQueue) {
                    lookaheadQueue.add(0, prevActive)
                }
            }
            wakeup.value += 1
        }
    }

    fun invalidateAll() {
        val jobToCancel: Job?
        synchronized(lock) {
            activePlaybackKey = null
            lookaheadQueue.clear()
            cache.values.forEach { it.markFailed() }
            cache.clear()
            jobToCancel = currentEntryJob
            currentEntryJob = null
            currentSynthesizingKey = null
            wakeup.value += 1
        }
        jobToCancel?.cancel()
    }

    fun isWorking(): Boolean = synchronized(lock) {
        currentSynthesizingKey != null || selectNextEntryLocked() != null
    }

    internal fun getEntryForTest(key: String): SentenceStreamEntry? = synchronized(lock) { cache[key] }

    internal fun cachedKeysForTest(): Set<String> = synchronized(lock) { cache.keys.toSet() }

    private fun selectNextEntryLocked(): SentenceStreamEntry? {
        val activeKey = activePlaybackKey
        if (activeKey != null) {
            val activeEntry = cache[activeKey]
            if (activeEntry != null && !activeEntry.completed && !activeEntry.failed) {
                return activeEntry
            }
        }
        for (key in lookaheadQueue) {
            val entry = cache[key]
            if (entry != null && !entry.completed && !entry.failed) {
                return entry
            }
        }
        return null
    }

    private fun trimCacheLocked() {
        if (cache.size <= maxEntries) return
        val protectedKeys = HashSet<String>().apply {
            activePlaybackKey?.let { add(it) }
            currentSynthesizingKey?.let { add(it) }
            addAll(lookaheadQueue)
        }
        val iterator = cache.entries.iterator()
        while (cache.size > maxEntries && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in protectedKeys) {
                iterator.remove()
            }
        }
    }

    private fun ensureWorkerLocked() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch(dispatcher) {
            runWorkerLoop()
        }
    }

    private suspend fun runWorkerLoop() {
        while (currentCoroutineContext().isActive) {
            val nextEntry: SentenceStreamEntry?
            val observedWakeup: Int
            synchronized(lock) {
                observedWakeup = wakeup.value
                nextEntry = selectNextEntryLocked()
                currentSynthesizingKey = nextEntry?.key
            }

            if (nextEntry == null) {
                wakeup.first { it != observedWakeup }
                continue
            }

            val job = scope.launch(dispatcher) {
                try {
                    var clauseIdx = 0
                    synthesizeStreamFn(
                        nextEntry.text,
                        nextEntry.voice,
                        nextEntry.speed,
                        nextEntry.qualityMode
                    ).collect { chunk ->
                        currentCoroutineContext().ensureActive()
                        nextEntry.setChunk(clauseIdx, chunk)
                        clauseIdx++
                    }
                    nextEntry.markCompleted()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    nextEntry.markFailed()
                }
            }

            synchronized(lock) {
                currentEntryJob = job
                val activeKey = activePlaybackKey
                val activeEntry = activeKey?.let { cache[it] }
                if (activeKey != null && activeKey != nextEntry.key && activeEntry != null && !activeEntry.completed && !activeEntry.failed) {
                    job.cancel()
                }
            }

            try {
                job.join()
            } catch (e: CancellationException) {
                job.cancel()
                throw e
            } finally {
                synchronized(lock) {
                    if (currentEntryJob === job) {
                        currentEntryJob = null
                    }
                    if (currentSynthesizingKey == nextEntry.key) {
                        currentSynthesizingKey = null
                    }
                }
            }
        }
    }
}

/** Dual-engine speech: Supertonic 3 HD neural TTS when installed and enabled, with system TextToSpeech baseline & fallback. */
class Narrator(
    private val context: Context,
    private val qualityStore: SupertonicQualityStore = SupertonicQualityStore.getInstance(context),
) : Narration, AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val preferences = context.getSharedPreferences("supertonic_prefs", Context.MODE_PRIVATE)

    val voicePackService = VoicePackService(context)

    private val initialSpeed = preferences.getFloat("speed", 1f)
    private val initialUseHd = preferences.getBoolean("use_hd", true)
    private val initialVoice = preferences.getString("voice", SupertonicManifest.DEFAULT_VOICE) ?: SupertonicManifest.DEFAULT_VOICE

    private val mutableState = MutableStateFlow(
        VoiceState(
            speed = initialSpeed,
            useHd = initialUseHd,
            selectedVoice = initialVoice,
            packState = voicePackService.checkInitialState(),
        )
    )
    override val state = mutableState.asStateFlow()

    private var engine: TextToSpeech? = null
    private var initializing = true
    private var initializationGeneration = 0L
    private var generation = 0L
    private var completion: (() -> Unit)? = null
    private var buffers: List<String> = emptyList()
    private var bufferIndex = 0
    private val utteranceId: String get() = "$generation:$bufferIndex"

    private var systemReady = false
    private var systemStatus = "Starting device voice…"

    private val engineLock = Any()
    private var supertonicEngine: SupertonicEngine? = null
    private var warmupJob: Job? = null

    private val audioTrackLock = Any()
    private var audioTrack: AudioTrack? = null
    private var audioTrackTotalWrittenSamples = 0L
    private var transitioningNaturally = false

    private var playbackJob: Job? = null
    private var idleTimeoutJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    internal val prefetchPipeline = HdPrefetchPipeline(
        scope = scope,
        dispatcher = Dispatchers.Default,
        maxEntries = 4,
    ) { text, voice, speed, qualityMode ->
        getOrCreateSupertonicEngine().synthesizeStream(text, voice, speed, qualityMode)
    }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change -> if (change != AudioManager.AUDIOFOCUS_GAIN) pause() }, handler)
        .build()

    init {
        createEngine(context)
        observeVoicePackState()
        observeQualityMode()
    }

    private fun observeQualityMode() {
        scope.launch {
            qualityStore.qualityMode.collect { mode ->
                withContext(Dispatchers.Main) {
                    if (mutableState.value.qualityMode != mode) {
                        mutableState.value = mutableState.value.copy(qualityMode = mode)
                        prefetchPipeline.invalidateAll()
                    }
                }
            }
        }
    }

    private fun observeVoicePackState() {
        scope.launch {
            voicePackService.state.collect { packState ->
                handler.post {
                    mutableState.value = state.value.copy(packState = packState)
                    updateReadinessAndStatus()
                    if (packState is VoicePackState.Installed && state.value.useHd) {
                        prewarmEngineIfNeeded()
                    }
                }
            }
        }
    }

    private fun prewarmEngineIfNeeded() {
        val current = state.value
        if (!current.useHd || current.packState !is VoicePackState.Installed) return
        if (warmupJob?.isActive == true) return
        warmupJob = scope.launch(Dispatchers.Default) {
            runCatching {
                val eng = getOrCreateSupertonicEngine()
                eng.warmup()
            }
        }
    }

    private fun updateReadinessAndStatus() {
        val current = state.value
        val isHdActive = current.useHd && current.packState is VoicePackState.Installed
        if (isHdActive) {
            mutableState.value = current.copy(
                ready = true,
                initializing = false,
                status = "Offline HD voice ready · ${current.selectedVoice}"
            )
        } else {
            mutableState.value = current.copy(
                ready = systemReady,
                initializing = initializing,
                status = systemStatus
            )
        }
    }

    private fun createEngine(context: Context) {
        val token = ++initializationGeneration
        initializing = true
        mutableState.value = state.value.copy(initializing = true)
        engine = TextToSpeech(context.applicationContext) { result ->
            handler.post { if (token == initializationGeneration) initialize(result) }
        }
    }

    private fun initialize(result: Int) {
        initializing = false
        val speech = engine ?: return
        if (result != TextToSpeech.SUCCESS) {
            systemReady = false
            systemStatus = "No device voice. Open voice settings to install one."
            updateReadinessAndStatus()
            return
        }
        val voice = speech.voices?.filter {
            it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired &&
                !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }?.sortedWith(compareBy({ if (it.locale.country == "GB") 0 else if (it.locale.country == "US") 1 else 2 }, { -it.quality }))?.firstOrNull()
        if (voice == null || speech.setVoice(voice) != TextToSpeech.SUCCESS) {
            systemReady = false
            systemStatus = "Install an offline English voice in device voice settings. You can still read all text."
            updateReadinessAndStatus()
            return
        }
        speech.setAudioAttributes(attributes)
        speech.setSpeechRate(state.value.speed)
        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                handler.post {
                    if (utteranceId == this@Narrator.utteranceId && state.value.playing) {
                        if (bufferIndex + 1 < buffers.size) {
                            bufferIndex++
                            speakBuffer()
                            return@post
                        }
                        mutableState.value = state.value.copy(playing = false)
                        val callback = completion
                        completion = null
                        audio.abandonAudioFocusRequest(focus)
                        callback?.invoke()
                    }
                }
            }
            @Deprecated("Required by Android TextToSpeech")
            override fun onError(utteranceId: String?) { speechError(utteranceId) }
            override fun onError(utteranceId: String?, errorCode: Int) { speechError(utteranceId) }
        })
        systemReady = true
        systemStatus = "Offline device voice ready"
        updateReadinessAndStatus()
    }

    private fun speechError(id: String?) {
        handler.post {
            if (id == utteranceId) {
                pause()
                systemReady = false
                systemStatus = "Voice data is unavailable. Install an offline English voice in voice settings."
                updateReadinessAndStatus()
            }
        }
    }

    private fun getOrCreateSupertonicEngine(): SupertonicEngine = synchronized(engineLock) {
        var eng = supertonicEngine
        if (eng == null) {
            eng = SupertonicEngine(voicePackService.getVoiceDir())
            supertonicEngine = eng
        }
        eng
    }

    private fun closeSupertonicEngine() = synchronized(engineLock) {
        supertonicEngine?.close()
        supertonicEngine = null
    }

    private fun getOrCreateAudioTrack(): AudioTrack = synchronized(audioTrackLock) {
        val existing = audioTrack
        if (existing != null && existing.state == AudioTrack.STATE_INITIALIZED) {
            return existing
        }
        runCatching { existing?.release() }
        val minBufferSize = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBufferSize, 44100 * 2)
        val created = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = created
        audioTrackTotalWrittenSamples = 0L
        created
    }

    private fun releaseAudioTrack() = synchronized(audioTrackLock) {
        val track = audioTrack
        audioTrack = null
        audioTrackTotalWrittenSamples = 0L
        if (track != null) {
            runCatching {
                track.pause()
                track.flush()
                track.stop()
                track.release()
            }
        }
    }

    private fun scheduleIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = scope.launch {
            delay(180_000L) // 3 minutes
            if (!state.value.playing && !prefetchPipeline.isWorking()) {
                closeSupertonicEngine()
            }
        }
    }

    private fun cancelIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
    }

    private fun stopActivePlayback(clearLookahead: Boolean, keepAudioTrack: Boolean = false) {
        generation++
        completion = null
        buffers = emptyList()
        bufferIndex = 0

        playbackJob?.cancel()
        playbackJob = null

        if (clearLookahead) {
            prefetchPipeline.invalidateAll()
        } else {
            prefetchPipeline.clearActivePlayback(keepInLookahead = true)
        }

        if (!keepAudioTrack) {
            releaseAudioTrack()
        }

        engine?.stop()
        if (!keepAudioTrack) {
            audio.abandonAudioFocusRequest(focus)
            mutableState.value = state.value.copy(playing = false)
            scheduleIdleTimeout()
        }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        val seamlessTransition = transitioningNaturally
        stopActivePlayback(clearLookahead = false, keepAudioTrack = seamlessTransition)
        if (!state.value.ready) {
            if (seamlessTransition) {
                releaseAudioTrack()
                mutableState.value = state.value.copy(playing = false)
                audio.abandonAudioFocusRequest(focus)
                scheduleIdleTimeout()
            }
            return
        }
        if (!seamlessTransition && audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mutableState.value = state.value.copy(status = "Audio is in use. Try play again.")
            return
        }

        val useHd = state.value.useHd && state.value.packState is VoicePackState.Installed
        if (useHd) {
            speakHd(text, onDone)
        } else {
            if (seamlessTransition) releaseAudioTrack()
            speakSystem(text, onDone)
        }
    }

    private fun speakSystem(text: String, onDone: () -> Unit) {
        buffers = SpeechBuffers.split(text, TextToSpeech.getMaxSpeechInputLength())
        if (buffers.isEmpty()) { audio.abandonAudioFocusRequest(focus); return }
        bufferIndex = 0
        completion = onDone
        mutableState.value = state.value.copy(playing = true)
        speakBuffer()
    }

    private fun speakBuffer() {
        val parameters = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        val id = utteranceId
        if (engine?.speak(buffers[bufferIndex], TextToSpeech.QUEUE_FLUSH, parameters, id) != TextToSpeech.SUCCESS) speechError(id)
    }

    private fun speakHd(text: String, onDone: () -> Unit) {
        completion = onDone
        mutableState.value = state.value.copy(playing = true)
        cancelIdleTimeout()

        val voice = state.value.selectedVoice
        val speed = state.value.speed
        val qualityMode = state.value.qualityMode
        val myGeneration = generation

        // Synchronously claim or attach to the sentence entry BEFORE speak() returns,
        // so an immediate prepareNext(N+1) on the caller thread never evicts or cancels sentence N.
        val streamEntry = prefetchPipeline.claimForPlayback(text, voice, speed, qualityMode)

        playbackJob = scope.launch(Dispatchers.Default) {
            try {
                playPcmStream(streamEntry.asFlow())

                withContext(Dispatchers.Main) {
                    if (myGeneration == generation) {
                        prefetchPipeline.clearActivePlayback(keepInLookahead = false)
                        val cb = completion
                        completion = null
                        transitioningNaturally = true
                        try {
                            cb?.invoke()
                        } finally {
                            transitioningNaturally = false
                        }
                        if (myGeneration == generation) {
                            releaseAudioTrack()
                            mutableState.value = state.value.copy(playing = false)
                            audio.abandonAudioFocusRequest(focus)
                            scheduleIdleTimeout()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (myGeneration == generation) {
                        prefetchPipeline.clearActivePlayback(keepInLookahead = false)
                        releaseAudioTrack()
                        mutableState.value = state.value.copy(playing = false)
                        audio.abandonAudioFocusRequest(focus)
                        scheduleIdleTimeout()
                    }
                }
            }
        }
    }

    private suspend fun playPcmStream(chunks: Flow<ShortArray>) = withContext(Dispatchers.IO) {
        val track = getOrCreateAudioTrack()
        var sentenceWrittenSamples = 0L
        var targetHeadSamples = 0L
        try {
            chunks.collect { pcmChunk ->
                currentCoroutineContext().ensureActive()
                if (pcmChunk.isEmpty()) return@collect

                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }

                val sliceSize = 4096
                var offset = 0
                while (offset < pcmChunk.size) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(sliceSize, pcmChunk.size - offset)
                    val written = track.write(pcmChunk, offset, count, AudioTrack.WRITE_BLOCKING)
                    if (written > 0) {
                        sentenceWrittenSamples += written
                        synchronized(audioTrackLock) {
                            audioTrackTotalWrittenSamples += written
                            targetHeadSamples = audioTrackTotalWrittenSamples
                        }
                        offset += written
                    } else if (written < 0) {
                        break
                    }
                }
            }

            if (sentenceWrittenSamples > 0L) {
                while (currentCoroutineContext().isActive &&
                    track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < targetHeadSamples) {
                    delay(5)
                }
            }
        } catch (e: CancellationException) {
            releaseAudioTrack()
            throw e
        } catch (e: Throwable) {
            releaseAudioTrack()
            throw e
        }
    }

    override fun prepareNext(text: String) {
        prepareNextSentences(listOf(text))
    }

    fun prepareNextSentences(texts: List<String>) {
        if (!state.value.useHd || state.value.packState !is VoicePackState.Installed) return
        val voice = state.value.selectedVoice
        val speed = state.value.speed
        val qualityMode = state.value.qualityMode
        prefetchPipeline.enqueueLookahead(texts, voice, speed, qualityMode)
    }

    override fun pause() {
        stopActivePlayback(clearLookahead = false, keepAudioTrack = false)
    }

    override fun changeSpeed(): Float {
        val choices = listOf(.75f, 1f, 1.25f, 1.5f, 2f)
        val next = choices[(choices.indexOf(state.value.speed) + 1) % choices.size]
        stopActivePlayback(clearLookahead = true, keepAudioTrack = false)
        engine?.setSpeechRate(next)
        preferences.edit().putFloat("speed", next).apply()
        mutableState.value = state.value.copy(speed = next)
        return next
    }

    override fun setEnginePreference(useHd: Boolean) {
        prefetchPipeline.invalidateAll()
        preferences.edit().putBoolean("use_hd", useHd).apply()
        mutableState.value = state.value.copy(useHd = useHd)
        updateReadinessAndStatus()
        if (useHd && state.value.packState is VoicePackState.Installed) {
            prewarmEngineIfNeeded()
        }
    }

    override fun setVoicePreference(voice: String) {
        prefetchPipeline.invalidateAll()
        preferences.edit().putString("voice", voice).apply()
        mutableState.value = state.value.copy(selectedVoice = voice)
        updateReadinessAndStatus()
    }

    override fun setQualityMode(mode: SupertonicQualityMode) {
        mutableState.value = state.value.copy(qualityMode = mode)
        prefetchPipeline.invalidateAll()
        scope.launch {
            qualityStore.setQualityMode(mode)
        }
    }

    override fun downloadVoicePack() {
        voicePackService.download()
    }

    override fun cancelVoicePackDownload() {
        voicePackService.cancel()
    }

    override fun deleteVoicePack() {
        stopActivePlayback(clearLookahead = true, keepAudioTrack = false)
        warmupJob?.cancel()
        warmupJob = null
        closeSupertonicEngine()
        voicePackService.delete()
        updateReadinessAndStatus()
    }

    override fun previewVoice(onDone: () -> Unit) {
        val sample = "AnchorReader brings books and papers to life on your device."
        speak(sample, onDone)
    }

    fun refresh(context: Context) {
        if (!state.value.ready && !initializing && (!state.value.useHd || state.value.packState !is VoicePackState.Installed)) {
            pause()
            engine?.shutdown()
            createEngine(context)
        }
    }

    override fun close() {
        stopActivePlayback(clearLookahead = true, keepAudioTrack = false)
        warmupJob?.cancel()
        warmupJob = null
        scope.cancel()
        closeSupertonicEngine()
        initializationGeneration++
        engine?.shutdown()
        engine = null
        handler.removeCallbacksAndMessages(null)
    }
}
