package com.anchorreader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class VoiceState(
    val ready: Boolean = false,
    val initializing: Boolean = true,
    val playing: Boolean = false,
    val status: String = "Starting device voice…",
    val speed: Float = 1f,
)

interface Narration {
    val state: StateFlow<VoiceState>
    fun speak(text: String, onDone: () -> Unit)
    fun pause()
    fun changeSpeed(): Float
}

/** Device speech only. Never sends book text to a network voice. */
class Narrator(context: Context) : Narration, AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutableState = MutableStateFlow(VoiceState())
    override val state = mutableState.asStateFlow()
    private var engine: TextToSpeech? = null
    private var initializing = true
    private var initializationGeneration = 0L
    private var generation = 0L
    private var completion: (() -> Unit)? = null
    private var buffers: List<String> = emptyList()
    private var bufferIndex = 0
    private val utteranceId: String get() = "$generation:$bufferIndex"
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change -> if (change != AudioManager.AUDIOFOCUS_GAIN) pause() }, handler)
        .build()

    init {
        createEngine(context)
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
            mutableState.value = state.value.copy(initializing = false, status = "No device voice. Open voice settings to install one.")
            return
        }
        val voice = speech.voices?.filter {
            it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired &&
                !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }?.sortedWith(compareBy({ if (it.locale.country == "GB") 0 else if (it.locale.country == "US") 1 else 2 }, { -it.quality }))?.firstOrNull()
        if (voice == null || speech.setVoice(voice) != TextToSpeech.SUCCESS) {
            mutableState.value = state.value.copy(initializing = false, status = "Install an offline English voice in device voice settings. You can still read all text.")
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
        mutableState.value = state.value.copy(ready = true, initializing = false, status = "Offline device voice ready")
    }

    private fun speechError(id: String?) {
        handler.post {
            if (id == utteranceId) {
                pause()
                mutableState.value = state.value.copy(ready = false, status = "Voice data is unavailable. Install an offline English voice in voice settings.")
            }
        }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        pause()
        if (!state.value.ready) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mutableState.value = state.value.copy(status = "Audio is in use. Try play again.")
            return
        }
        buffers = SpeechBuffers.split(text, TextToSpeech.getMaxSpeechInputLength())
        if (buffers.isEmpty()) { audio.abandonAudioFocusRequest(focus); return }
        bufferIndex = 0
        completion = onDone
        mutableState.value = state.value.copy(playing = true, status = "Offline device voice ready")
        speakBuffer()
    }

    private fun speakBuffer() {
        val parameters = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        val id = utteranceId
        if (engine?.speak(buffers[bufferIndex], TextToSpeech.QUEUE_FLUSH, parameters, id) != TextToSpeech.SUCCESS) speechError(id)
    }

    override fun pause() {
        generation++
        completion = null
        buffers = emptyList()
        bufferIndex = 0
        engine?.stop()
        audio.abandonAudioFocusRequest(focus)
        mutableState.value = state.value.copy(playing = false)
    }

    override fun changeSpeed(): Float {
        val choices = listOf(.75f, 1f, 1.25f, 1.5f, 2f)
        val next = choices[(choices.indexOf(state.value.speed) + 1) % choices.size]
        pause()
        engine?.setSpeechRate(next)
        mutableState.value = state.value.copy(speed = next)
        return next
    }

    fun refresh(context: Context) {
        if (!state.value.ready && !initializing) {
            pause()
            engine?.shutdown()
            createEngine(context)
        }
    }

    override fun close() {
        pause()
        initializationGeneration++
        engine?.shutdown()
        engine = null
        handler.removeCallbacksAndMessages(null)
    }
}
