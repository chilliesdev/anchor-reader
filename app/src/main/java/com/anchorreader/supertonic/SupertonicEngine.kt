package com.anchorreader.supertonic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.EnumSet
import java.util.Random

data class ComponentBreakdown(
    val preprocessMs: Double,
    val durationPredictorMs: Double,
    val textEncoderMs: Double,
    val vectorEstimatorMs: Double,
    val vocoderMs: Double,
    val totalInferenceMs: Double,
    val audioDurationSeconds: Float,
    val rtf: Double,
    val audioSamples: Int,
)

class SupertonicEngine(
    private val voiceDir: File,
    val intraOpThreads: Int = defaultIntraOpThreads(),
    val enableNnapi: Boolean = true,
) : AutoCloseable {

    companion object {
        fun defaultIntraOpThreads(cores: Int = Runtime.getRuntime().availableProcessors()): Int =
            (cores / 2).coerceIn(2, 4)
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var sessionOptions: OrtSession.SessionOptions? = null

    var activeProvider: String = "CPU"
        private set

    val sampleRate: Int
    val baseChunkSize: Int
    val chunkCompressFactor: Int
    val latentDim: Int

    val voiceParser: SupertonicVoiceParser
    val frontend: SupertonicTextFrontend

    private var dpSession: OrtSession? = null
    private var textEncSession: OrtSession? = null
    private var vectorEstSession: OrtSession? = null
    private var vocoderSession: OrtSession? = null

    private val inferenceMutex = Mutex()
    @Volatile
    private var isClosed = false

    init {
        val ttsJson = JSONObject(File(voiceDir, "tts.json").readText())
        val ae = ttsJson.optJSONObject("ae") ?: JSONObject()
        val ttl = ttsJson.optJSONObject("ttl") ?: JSONObject()
        sampleRate = ae.optInt("sample_rate", 44100)
        baseChunkSize = ae.optInt("base_chunk_size", 512)
        chunkCompressFactor = ttl.optInt("chunk_compress_factor", 2)
        latentDim = ttl.optInt("latent_dim", 64)

        voiceParser = SupertonicVoiceParser.parse(File(voiceDir, "voice.bin"))
        val indexerBytes = File(voiceDir, "unicode_indexer.bin").readBytes()
        frontend = SupertonicTextFrontend(SupertonicTextFrontend.loadIndexer(indexerBytes))

        initSessions()
    }

    private fun isNnapiSupported(): Boolean {
        return try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
                OrtEnvironment.getAvailableProviders().contains(OrtProvider.NNAPI)
        } catch (_: Throwable) {
            false
        }
    }

    private fun createSessionOptions(useNnapi: Boolean): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(intraOpThreads)
        opts.setInterOpNumThreads(1)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        opts.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
        if (useNnapi) {
            try {
                opts.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
            } catch (_: Throwable) { }
        }
        return opts
    }

    private fun initSessions() {
        if (enableNnapi && isNnapiSupported()) {
            try {
                val opts = createSessionOptions(useNnapi = true)
                sessionOptions = opts
                dpSession = env.createSession(File(voiceDir, "duration_predictor.int8.onnx").absolutePath, opts)
                textEncSession = env.createSession(File(voiceDir, "text_encoder.int8.onnx").absolutePath, opts)
                vectorEstSession = env.createSession(File(voiceDir, "vector_estimator.int8.onnx").absolutePath, opts)
                vocoderSession = env.createSession(File(voiceDir, "vocoder.int8.onnx").absolutePath, opts)
                activeProvider = "NNAPI"
                return
            } catch (_: Throwable) {
                closeSessions()
            }
        }

        fallbackToCpu()
    }

    private fun fallbackToCpu() {
        closeSessions()
        val opts = createSessionOptions(useNnapi = false)
        sessionOptions = opts
        dpSession = env.createSession(File(voiceDir, "duration_predictor.int8.onnx").absolutePath, opts)
        textEncSession = env.createSession(File(voiceDir, "text_encoder.int8.onnx").absolutePath, opts)
        vectorEstSession = env.createSession(File(voiceDir, "vector_estimator.int8.onnx").absolutePath, opts)
        vocoderSession = env.createSession(File(voiceDir, "vocoder.int8.onnx").absolutePath, opts)
        activeProvider = "CPU"
    }

    fun synthesizeStream(
        text: String,
        voiceName: String = SupertonicManifest.DEFAULT_VOICE,
        speed: Float = 1.0f,
        qualityMode: SupertonicQualityMode = SupertonicQualityMode.STUDIO_5_STEP
    ): Flow<ShortArray> = flow {
        val clauses = SupertonicTextFrontend.splitStreamClauses(text)
        if (clauses.isEmpty()) return@flow

        val sid = SupertonicVoiceParser.sidForVoice(voiceName)
        val slice = voiceParser.getVoiceSlice(sid)

        for (clause in clauses) {
            currentCoroutineContext().ensureActive()
            val chunkAudio = inferenceMutex.withLock {
                currentCoroutineContext().ensureActive()
                synthesizeChunkWithFallback(clause, slice, speed, qualityMode)
            }
            if (chunkAudio.isNotEmpty()) {
                emit(chunkAudio)
            }
        }
    }

    suspend fun synthesize(
        text: String,
        voiceName: String = SupertonicManifest.DEFAULT_VOICE,
        speed: Float = 1.0f,
        qualityMode: SupertonicQualityMode = SupertonicQualityMode.STUDIO_5_STEP
    ): ShortArray {
        val buffers = mutableListOf<ShortArray>()
        synthesizeStream(text, voiceName, speed, qualityMode).collect {
            buffers.add(it)
        }
        if (buffers.isEmpty()) return ShortArray(0)
        val totalLen = buffers.sumOf { it.size }
        val result = ShortArray(totalLen)
        var offset = 0
        for (buf in buffers) {
            System.arraycopy(buf, 0, result, offset, buf.size)
            offset += buf.size
        }
        return result
    }

    private suspend fun synthesizeChunkWithFallback(
        chunk: String,
        slice: SupertonicVoiceParser.VoiceStyleSlice,
        speed: Float,
        qualityMode: SupertonicQualityMode
    ): ShortArray {
        if (isClosed) return ShortArray(0)
        return if (activeProvider == "NNAPI") {
            try {
                synthesizeChunk(chunk, slice, speed, qualityMode)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fallbackToCpu()
                synthesizeChunk(chunk, slice, speed, qualityMode)
            }
        } else {
            synthesizeChunk(chunk, slice, speed, qualityMode)
        }
    }

    private suspend fun synthesizeChunk(
        chunk: String,
        slice: SupertonicVoiceParser.VoiceStyleSlice,
        speed: Float,
        qualityMode: SupertonicQualityMode
    ): ShortArray {
        if (isClosed) return ShortArray(0)
        currentCoroutineContext().ensureActive()

        val preprocessed = frontend.preprocess(chunk, "en")
        if (preprocessed.isEmpty()) return ShortArray(0)
        val (tokens, mask) = frontend.encode(preprocessed)
        val seqLen = tokens.size.toLong()

        val dpSess = dpSession ?: return ShortArray(0)
        val textEncSess = textEncSession ?: return ShortArray(0)
        val vectorEstSess = vectorEstSession ?: return ShortArray(0)
        val vocoderSess = vocoderSession ?: return ShortArray(0)

        // 1. Duration Predictor
        val dpTextIds = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, seqLen))
        val dpStyleDp = OnnxTensor.createTensor(env, FloatBuffer.wrap(slice.dpData), slice.dpShape)
        val dpTextMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, seqLen))

        val dpInputs = mapOf(
            findInput(dpSess, "text", "id") to dpTextIds,
            findInput(dpSess, "style", "dp") to dpStyleDp,
            findInput(dpSess, "mask") to dpTextMask,
        )
        val durSeconds: Float
        dpSess.run(dpInputs).use { out ->
            val tensor = out.get(0) as OnnxTensor
            val buf = tensor.floatBuffer
            durSeconds = buf.get(0)
        }
        dpTextIds.close(); dpStyleDp.close(); dpTextMask.close()

        val adjustedDur = (if (speed > 0f) durSeconds / speed else durSeconds).coerceAtLeast(0.1f)
        val wavLen = (adjustedDur * sampleRate).toInt().coerceAtLeast(1)
        val wavChunkSize = baseChunkSize * chunkCompressFactor
        val latentLen = ((wavLen + wavChunkSize - 1) / wavChunkSize).coerceIn(1, 10000)
        val latentDimTotal = latentDim * chunkCompressFactor
        val latentTotalElements = latentDimTotal * latentLen

        // 2. Text Encoder
        val teTextIds = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, seqLen))
        val teStyleTtl = OnnxTensor.createTensor(env, FloatBuffer.wrap(slice.ttlData), slice.ttlShape)
        val teTextMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, seqLen))

        val teInputs = mapOf(
            findInput(textEncSess, "text", "id") to teTextIds,
            findInput(textEncSess, "style", "ttl") to teStyleTtl,
            findInput(textEncSess, "mask") to teTextMask,
        )
        val textEmbTensor: OnnxTensor
        val textEncResult = textEncSess.run(teInputs)
        textEmbTensor = textEncResult.get(0) as OnnxTensor
        teTextIds.close(); teStyleTtl.close(); teTextMask.close()

        // 3. Vector Estimator (parameterized flow matching steps: 3 or 5)
        val deterministicRandom = Random(42L)
        val xtFlat = FloatArray(latentTotalElements) { (deterministicRandom.nextGaussian() * 0.5f).toFloat() }
        val latentMaskArray = FloatArray(latentLen) { 1.0f }
        val numSteps = qualityMode.steps
        val totalStepTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(numSteps.toFloat())), longArrayOf(1))
        val latentMaskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(latentMaskArray), longArrayOf(1, 1, latentLen.toLong()))
        val veStyleTtl = OnnxTensor.createTensor(env, FloatBuffer.wrap(slice.ttlData), slice.ttlShape)
        val veTextMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, seqLen))

        for (step in 0 until numSteps) {
            currentCoroutineContext().ensureActive()
            val noisyLatentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(xtFlat), longArrayOf(1, latentDimTotal.toLong(), latentLen.toLong()))
            val currentStepTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1))

            val veInputs = mapOf(
                findInput(vectorEstSess, "noisy", "latent", "x") to noisyLatentTensor,
                findInput(vectorEstSess, "current", "step") to currentStepTensor,
                findInput(vectorEstSess, "text_emb", "emb") to textEmbTensor,
                findInput(vectorEstSess, "style", "ttl") to veStyleTtl,
                findInput(vectorEstSess, "latent_mask") to latentMaskTensor,
                findInput(vectorEstSess, "text_mask") to veTextMask,
                findInput(vectorEstSess, "total") to totalStepTensor,
            )

            vectorEstSess.run(veInputs).use { out ->
                val denoised = out.get(0) as OnnxTensor
                denoised.floatBuffer.get(xtFlat)
            }
            noisyLatentTensor.close()
            currentStepTensor.close()
        }
        textEmbTensor.close()
        textEncResult.close()
        totalStepTensor.close()
        latentMaskTensor.close()
        veStyleTtl.close()
        veTextMask.close()

        // 4. Vocoder
        currentCoroutineContext().ensureActive()
        val finalLatentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(xtFlat), longArrayOf(1, latentDimTotal.toLong(), latentLen.toLong()))
        val vocInputs = mapOf(vocoderSess.inputNames.first() to finalLatentTensor)
        val pcm16: ShortArray
        vocoderSess.run(vocInputs).use { out ->
            val wavTensor = out.get(0) as OnnxTensor
            val wavBuf = wavTensor.floatBuffer
            val totalSamples = wavBuf.remaining()
            val sampleCount = minOf(wavLen, totalSamples)
            pcm16 = ShortArray(sampleCount)
            for (i in 0 until sampleCount) {
                val s = wavBuf.get().coerceIn(-1.0f, 1.0f)
                pcm16[i] = (s * 32767.0f).toInt().toShort()
            }
        }
        finalLatentTensor.close()
        return pcm16
    }

    suspend fun benchmarkComponentBreakdown(
        text: String,
        voiceName: String = SupertonicManifest.DEFAULT_VOICE,
        speed: Float = 1.0f,
        qualityMode: SupertonicQualityMode = SupertonicQualityMode.STUDIO_5_STEP
    ): ComponentBreakdown = inferenceMutex.withLock {
        if (isClosed) throw IllegalStateException("Engine is closed")
        val sid = SupertonicVoiceParser.sidForVoice(voiceName)
        val slice = voiceParser.getVoiceSlice(sid)

        val tPre0 = System.nanoTime()
        val preprocessed = frontend.preprocess(text, "en")
        val (tokens, mask) = frontend.encode(preprocessed)
        val tPre1 = System.nanoTime()
        val preprocessMs = (tPre1 - tPre0) / 1_000_000.0

        val seqLen = tokens.size.toLong()
        val dpSess = dpSession ?: throw IllegalStateException("dpSession is null")
        val textEncSess = textEncSession ?: throw IllegalStateException("textEncSession is null")
        val vectorEstSess = vectorEstSession ?: throw IllegalStateException("vectorEstSession is null")
        val vocoderSess = vocoderSession ?: throw IllegalStateException("vocoderSession is null")

        // 1. Duration Predictor
        val tDp0 = System.nanoTime()
        val dpTextIds = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, seqLen))
        val dpStyleDp = OnnxTensor.createTensor(env, FloatBuffer.wrap(slice.dpData), slice.dpShape)
        val dpTextMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, seqLen))

        val dpInputs = mapOf(
            findInput(dpSess, "text", "id") to dpTextIds,
            findInput(dpSess, "style", "dp") to dpStyleDp,
            findInput(dpSess, "mask") to dpTextMask,
        )
        val durSeconds: Float
        dpSess.run(dpInputs).use { out ->
            val tensor = out.get(0) as OnnxTensor
            val buf = tensor.floatBuffer
            durSeconds = buf.get(0)
        }
        dpTextIds.close(); dpStyleDp.close(); dpTextMask.close()
        val tDp1 = System.nanoTime()
        val dpMs = (tDp1 - tDp0) / 1_000_000.0

        val adjustedDur = (if (speed > 0f) durSeconds / speed else durSeconds).coerceAtLeast(0.1f)
        val wavLen = (adjustedDur * sampleRate).toInt().coerceAtLeast(1)
        val wavChunkSize = baseChunkSize * chunkCompressFactor
        val latentLen = ((wavLen + wavChunkSize - 1) / wavChunkSize).coerceIn(1, 10000)
        val latentDimTotal = latentDim * chunkCompressFactor
        val latentTotalElements = latentDimTotal * latentLen

        // 2. Text Encoder
        val tTe0 = System.nanoTime()
        val teTextIds = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, seqLen))
        val teStyleTtl = OnnxTensor.createTensor(env, FloatBuffer.wrap(slice.ttlData), slice.ttlShape)
        val teTextMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, seqLen))

        val teInputs = mapOf(
            findInput(textEncSess, "text", "id") to teTextIds,
            findInput(textEncSess, "style", "ttl") to teStyleTtl,
            findInput(textEncSess, "mask") to teTextMask,
        )
        val textEmbTensor: OnnxTensor
        val textEncResult = textEncSess.run(teInputs)
        textEmbTensor = textEncResult.get(0) as OnnxTensor
        teTextIds.close(); teStyleTtl.close(); teTextMask.close()
        val tTe1 = System.nanoTime()
        val teMs = (tTe1 - tTe0) / 1_000_000.0

        // 3. Vector Estimator
        val tVe0 = System.nanoTime()
        val deterministicRandom = Random(42L)
        val xtFlat = FloatArray(latentTotalElements) { (deterministicRandom.nextGaussian() * 0.5f).toFloat() }
        val latentMaskArray = FloatArray(latentLen) { 1.0f }
        val numSteps = qualityMode.steps
        val totalStepTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(numSteps.toFloat())), longArrayOf(1))
        val latentMaskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(latentMaskArray), longArrayOf(1, 1, latentLen.toLong()))
        val veStyleTtl = OnnxTensor.createTensor(env, FloatBuffer.wrap(slice.ttlData), slice.ttlShape)
        val veTextMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, seqLen))

        for (step in 0 until numSteps) {
            val noisyLatentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(xtFlat), longArrayOf(1, latentDimTotal.toLong(), latentLen.toLong()))
            val currentStepTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1))

            val veInputs = mapOf(
                findInput(vectorEstSess, "noisy", "latent", "x") to noisyLatentTensor,
                findInput(vectorEstSess, "current", "step") to currentStepTensor,
                findInput(vectorEstSess, "text_emb", "emb") to textEmbTensor,
                findInput(vectorEstSess, "style", "ttl") to veStyleTtl,
                findInput(vectorEstSess, "latent_mask") to latentMaskTensor,
                findInput(vectorEstSess, "text_mask") to veTextMask,
                findInput(vectorEstSess, "total") to totalStepTensor,
            )

            vectorEstSess.run(veInputs).use { out ->
                val denoised = out.get(0) as OnnxTensor
                denoised.floatBuffer.get(xtFlat)
            }
            noisyLatentTensor.close()
            currentStepTensor.close()
        }
        textEmbTensor.close()
        textEncResult.close()
        totalStepTensor.close()
        latentMaskTensor.close()
        veStyleTtl.close()
        veTextMask.close()
        val tVe1 = System.nanoTime()
        val veMs = (tVe1 - tVe0) / 1_000_000.0

        // 4. Vocoder
        val tVoc0 = System.nanoTime()
        val finalLatentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(xtFlat), longArrayOf(1, latentDimTotal.toLong(), latentLen.toLong()))
        val vocInputs = mapOf(vocoderSess.inputNames.first() to finalLatentTensor)
        var sampleCount = 0
        vocoderSess.run(vocInputs).use { out ->
            val wavTensor = out.get(0) as OnnxTensor
            val wavBuf = wavTensor.floatBuffer
            val totalSamples = wavBuf.remaining()
            sampleCount = minOf(wavLen, totalSamples)
        }
        finalLatentTensor.close()
        val tVoc1 = System.nanoTime()
        val vocoderMs = (tVoc1 - tVoc0) / 1_000_000.0

        val totalMs = preprocessMs + dpMs + teMs + veMs + vocoderMs
        val audioDurationSeconds = sampleCount.toFloat() / sampleRate
        val rtf = if (audioDurationSeconds > 0) (totalMs / 1000.0) / audioDurationSeconds else 0.0

        ComponentBreakdown(
            preprocessMs = preprocessMs,
            durationPredictorMs = dpMs,
            textEncoderMs = teMs,
            vectorEstimatorMs = veMs,
            vocoderMs = vocoderMs,
            totalInferenceMs = totalMs,
            audioDurationSeconds = audioDurationSeconds,
            rtf = rtf,
            audioSamples = sampleCount,
        )
    }

    private fun findInput(session: OrtSession, vararg hints: String): String {
        for (name in session.inputNames) {
            val lower = name.lowercase()
            if (hints.all { lower.contains(it) }) return name
        }
        for (name in session.inputNames) {
            val lower = name.lowercase()
            if (hints.any { lower.contains(it) }) return name
        }
        return session.inputNames.first()
    }

    override fun close() {
        isClosed = true
        closeSessions()
        env.close()
    }

    private fun closeSessions() {
        try { dpSession?.close() } catch (_: Throwable) {}
        try { textEncSession?.close() } catch (_: Throwable) {}
        try { vectorEstSession?.close() } catch (_: Throwable) {}
        try { vocoderSession?.close() } catch (_: Throwable) {}
        try { sessionOptions?.close() } catch (_: Throwable) {}
        dpSession = null
        textEncSession = null
        vectorEstSession = null
        vocoderSession = null
        sessionOptions = null
    }
}
