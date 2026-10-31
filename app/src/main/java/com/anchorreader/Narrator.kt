package com.anchorreader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOf
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

    private var supertonicEngine: SupertonicEngine? = null
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var lookaheadJob: Job? = null
    private var lookaheadCache: Pair<String, ShortArray>? = null
    private var lookaheadTargetKey: String? = null
    private var idleTimeoutJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
                        lookaheadJob?.cancel()
                        lookaheadJob = null
                        lookaheadCache = null
                        lookaheadTargetKey = null
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
                }
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

    private fun getOrCreateSupertonicEngine(): SupertonicEngine {
        var eng = supertonicEngine
        if (eng == null) {
            eng = SupertonicEngine(voicePackService.getVoiceDir())
            supertonicEngine = eng
        }
        return eng
    }

    private fun scheduleIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = scope.launch {
            delay(180_000L) // 3 minutes
            if (!state.value.playing && lookaheadJob?.isActive != true) {
                supertonicEngine?.close()
                supertonicEngine = null
            }
        }
    }

    private fun cancelIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
    }

    private fun buildCacheKey(voice: String, speed: Float, qualityMode: SupertonicQualityMode, text: String): String =
        "$voice:$speed:${qualityMode.name}:$text"

    private fun stopActivePlayback(clearLookahead: Boolean) {
        generation++
        completion = null
        buffers = emptyList()
        bufferIndex = 0

        playbackJob?.cancel()
        playbackJob = null

        if (clearLookahead) {
            lookaheadJob?.cancel()
            lookaheadJob = null
            lookaheadCache = null
            lookaheadTargetKey = null
        }

        audioTrack?.let {
            runCatching {
                it.pause()
                it.flush()
                it.stop()
                it.release()
            }
        }
        audioTrack = null

        engine?.stop()
        if (clearLookahead) {
            audio.abandonAudioFocusRequest(focus)
            mutableState.value = state.value.copy(playing = false)
            scheduleIdleTimeout()
        }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        stopActivePlayback(clearLookahead = false)
        if (!state.value.ready) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mutableState.value = state.value.copy(status = "Audio is in use. Try play again.")
            return
        }

        val useHd = state.value.useHd && state.value.packState is VoicePackState.Installed
        if (useHd) {
            speakHd(text, onDone)
        } else {
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
        val cacheKey = buildCacheKey(voice, speed, qualityMode, text)

        playbackJob = scope.launch(Dispatchers.Default) {
            try {
                var cachedPcm: ShortArray? = null

                if (lookaheadCache?.first == cacheKey) {
                    cachedPcm = lookaheadCache!!.second
                    lookaheadCache = null
                    lookaheadTargetKey = null
                } else if (lookaheadTargetKey == cacheKey && lookaheadJob?.isActive == true) {
                    lookaheadJob?.join()
                    if (lookaheadCache?.first == cacheKey) {
                        cachedPcm = lookaheadCache!!.second
                        lookaheadCache = null
                        lookaheadTargetKey = null
                    }
                }

                if (lookaheadTargetKey != cacheKey) {
                    lookaheadJob?.cancel()
                    lookaheadJob = null
                    lookaheadCache = null
                    lookaheadTargetKey = null
                }

                if (cachedPcm != null && cachedPcm.isNotEmpty()) {
                    playPcmStream(flowOf(cachedPcm))
                } else {
                    val eng = getOrCreateSupertonicEngine()
                    val stream = eng.synthesizeStream(text, voice, speed, qualityMode)
                    playPcmStream(stream)
                }

                withContext(Dispatchers.Main) {
                    mutableState.value = state.value.copy(playing = false)
                    audio.abandonAudioFocusRequest(focus)
                    scheduleIdleTimeout()
                    val cb = completion
                    completion = null
                    cb?.invoke()
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    mutableState.value = state.value.copy(playing = false)
                    audio.abandonAudioFocusRequest(focus)
                    scheduleIdleTimeout()
                }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    mutableState.value = state.value.copy(playing = false)
                    audio.abandonAudioFocusRequest(focus)
                    scheduleIdleTimeout()
                }
            }
        }
    }

    private suspend fun playPcmStream(chunks: Flow<ShortArray>) = withContext(Dispatchers.IO) {
        val minBufferSize = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBufferSize, 44100 * 2)
        val track = AudioTrack.Builder()
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
        audioTrack = track

        var totalWrittenSamples = 0L
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.playbackParams = PlaybackParams().apply {
                    setSpeed(state.value.speed)
                    setPitch(1.0f)
                }
            }
            var hasStartedPlaying = false

            chunks.buffer(Channel.UNLIMITED).collect { pcmChunk ->
                currentCoroutineContext().ensureActive()
                if (pcmChunk.isEmpty()) return@collect

                if (!hasStartedPlaying) {
                    track.play()
                    hasStartedPlaying = true
                }

                val sliceSize = 4096
                var offset = 0
                while (offset < pcmChunk.size) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(sliceSize, pcmChunk.size - offset)
                    val written = track.write(pcmChunk, offset, count, AudioTrack.WRITE_BLOCKING)
                    if (written > 0) {
                        totalWrittenSamples += written
                        offset += written
                    } else if (written < 0) {
                        break
                    }
                }
            }

            if (hasStartedPlaying) {
                while (currentCoroutineContext().isActive &&
                    track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    track.playbackHeadPosition < totalWrittenSamples) {
                    delay(20)
                }
            }
        } finally {
            runCatching {
                track.pause()
                track.flush()
                track.stop()
                track.release()
            }
            if (audioTrack === track) {
                audioTrack = null
            }
        }
    }

    override fun prepareNext(text: String) {
        if (!state.value.useHd || state.value.packState !is VoicePackState.Installed) return
        val voice = state.value.selectedVoice
        val speed = state.value.speed
        val qualityMode = state.value.qualityMode
        val cacheKey = buildCacheKey(voice, speed, qualityMode, text)

        if (lookaheadTargetKey == cacheKey && (lookaheadJob?.isActive == true || lookaheadCache?.first == cacheKey)) {
            return
        }

        lookaheadJob?.cancel()
        lookaheadJob = null
        lookaheadCache = null
        lookaheadTargetKey = cacheKey

        lookaheadJob = scope.launch(Dispatchers.Default) {
            try {
                val eng = getOrCreateSupertonicEngine()
                val pcm = eng.synthesize(text, voice, speed, qualityMode)
                if (isActive && pcm.isNotEmpty()) {
                    lookaheadCache = cacheKey to pcm
                }
            } catch (_: Exception) { }
        }
    }

    override fun pause() {
        stopActivePlayback(clearLookahead = true)
    }

    override fun changeSpeed(): Float {
        val choices = listOf(.75f, 1f, 1.25f, 1.5f, 2f)
        val next = choices[(choices.indexOf(state.value.speed) + 1) % choices.size]
        pause()
        engine?.setSpeechRate(next)
        preferences.edit().putFloat("speed", next).apply()
        mutableState.value = state.value.copy(speed = next)
        return next
    }

    override fun setEnginePreference(useHd: Boolean) {
        lookaheadJob?.cancel()
        lookaheadJob = null
        lookaheadCache = null
        lookaheadTargetKey = null
        preferences.edit().putBoolean("use_hd", useHd).apply()
        mutableState.value = state.value.copy(useHd = useHd)
        updateReadinessAndStatus()
    }

    override fun setVoicePreference(voice: String) {
        lookaheadJob?.cancel()
        lookaheadJob = null
        lookaheadCache = null
        lookaheadTargetKey = null
        preferences.edit().putString("voice", voice).apply()
        mutableState.value = state.value.copy(selectedVoice = voice)
        updateReadinessAndStatus()
    }

    override fun setQualityMode(mode: SupertonicQualityMode) {
        mutableState.value = state.value.copy(qualityMode = mode)
        lookaheadJob?.cancel()
        lookaheadJob = null
        lookaheadCache = null
        lookaheadTargetKey = null
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
        pause()
        supertonicEngine?.close()
        supertonicEngine = null
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
        pause()
        scope.cancel()
        supertonicEngine?.close()
        supertonicEngine = null
        initializationGeneration++
        engine?.shutdown()
        engine = null
        handler.removeCallbacksAndMessages(null)
    }
}
