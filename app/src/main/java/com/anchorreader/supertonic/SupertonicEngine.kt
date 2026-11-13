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
import java.util.concurrent.ConcurrentHashMap

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
    val enableNnapi: Boolean = false,
) : AutoCloseable {

    companion object {
        const val MAX_LATENT_ELEMENTS = 131072

        fun defaultIntraOpThreads(cores: Int = Runtime.getRuntime().availableProcessors()): Int =
            (cores / 2).coerceIn(2, 4)
    }

    private data class CachedModelInputs(
        val dpTextIds: String,
        val dpStyleDp: String,
        val dpTextMask: String,
        val teTextIds: String,
        val teStyleTtl: String,
        val teTextMask: String,
        val veNoisyLatent: String,
        val veCurrentStep: String,
        val veTextEmb: String,
        val veStyleTtl: String,
        val veLatentMask: String,
        val veTextMask: String,
        val veTotalStep: String,
        val vocoderInput: String,
    )

    private class VoiceTensorCache(
        val dpTensor: OnnxTensor,
        val ttlTensor: OnnxTensor,
    ) : AutoCloseable {
        override fun close() {
            try { dpTensor.close() } catch (_: Throwable) {}
            try { ttlTensor.close() } catch (_: Throwable) {}
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var sessionOptions: OrtSession.SessionOptions? = null
    private var cachedInputs: CachedModelInputs? = null
    private var stepTensors: Array<OnnxTensor>? = null
    private var totalStepTensors: Array<OnnxTensor>? = null
    private val voiceTensorCache = ConcurrentHashMap<Int, VoiceTensorCache>()

    private val gaussianNoiseCache = FloatArray(MAX_LATENT_ELEMENTS).also { arr ->
        val rng = Random(42L)
        for (i in arr.indices) {
            arr[i] = (rng.nextGaussian() * 0.5f).toFloat()
        }
    }

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
    @Volatile
    private var warmedUp = false

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

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for x86")
            || Build.HARDWARE.contains("ranchu")
            || Build.HARDWARE.contains("goldfish")
            || Build.PRODUCT.contains("sdk_google")
            || Build.PRODUCT.contains("sdk"))
    }

    private fun isNnapiSupported(): Boolean {
        if (isEmulator()) return false
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

    private fun resolveInputNames() {
        val dp = dpSession ?: return
        val te = textEncSession ?: return
        val ve = vectorEstSession ?: return
        val voc = vocoderSession ?: return
        cachedInputs = CachedModelInputs(
            dpTextIds = findInput(dp, "text", "id"),
            dpStyleDp = findInput(dp, "style", "dp"),
            dpTextMask = findInput(dp, "mask"),
            teTextIds = findInput(te, "text", "id"),
            teStyleTtl = findInput(te, "style", "ttl"),
            teTextMask = findInput(te, "mask"),
            veNoisyLatent = findInput(ve, "noisy", "latent", "x"),
            veCurrentStep = findInput(ve, "current", "step"),
            veTextEmb = findInput(ve, "text_emb", "emb"),
            veStyleTtl = findInput(ve, "style", "ttl"),
            veLatentMask = findInput(ve, "latent_mask"),
            veTextMask = findInput(ve, "text_mask"),
            veTotalStep = findInput(ve, "total"),
            vocoderInput = voc.inputNames.first()
        )
    }

    private fun initConstantTensors() {
        closeConstantTensors()
        stepTensors = Array(6) { step ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1))
        }
        totalStepTensors = Array(6) { total ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(total.toFloat())), longArrayOf(1))
        }
    }

    private fun closeConstantTensors() {
        stepTensors?.forEach { try { it.close() } catch (_: Throwable) {} }
        totalStepTensors?.forEach { try { it.close() } catch (_: Throwable) {} }
        stepTensors = null
        totalStepTensors = null
    }

    private fun getVoiceTensors(sid: Int): VoiceTensorCache {
        return voiceTensorCache.computeIfAbsent(sid) { _: Int ->
            val slice = voiceParser.getVoiceSlice(sid)
            val dpTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(slice.dpData), slice.dpShape)
            val ttlTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(slice.ttlData), slice.ttlShape)
            VoiceTensorCache(dpTensor, ttlTensor)
        }
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
                resolveInputNames()
                initConstantTensors()
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
        resolveInputNames()
        initConstantTensors()
        activeProvider = "CPU"
    }

    suspend fun warmup() {
        if (warmedUp || isClosed) return
        inferenceMutex.withLock {
            if (warmedUp || isClosed) return
            val sid = SupertonicVoiceParser.sidForVoice(SupertonicManifest.DEFAULT_VOICE)
            synthesizeChunkWithFallback("Hi.", sid, 1.0f, steps = 1, isClause = false)
            warmedUp = true
        }
    }

    fun synthesizeStream(
        text: String,
        voiceName: String = SupertonicManifest.DEFAULT_VOICE,
        speed: Float = 1.0f,
        qualityMode: SupertonicQualityMode = SupertonicQualityMode.STUDIO_5_STEP,
        initialStreamingChunk: Boolean = true
    ): Flow<ShortArray> = flow {
        val clauses = SupertonicTextFrontend.splitStreamClauses(text, initialStreamingChunk = initialStreamingChunk)
        if (clauses.isEmpty()) return@flow

        val sid = SupertonicVoiceParser.sidForVoice(voiceName)

        for ((index, clause) in clauses.withIndex()) {
            currentCoroutineContext().ensureActive()
            val isFirst = initialStreamingChunk && index == 0
            val isBridgeClause = initialStreamingChunk && index == 1 && clauses.size > 2
            val isIntraSentenceClause = index < clauses.size - 1 && !clause.endsWith(".") && !clause.endsWith("!") && !clause.endsWith("?")
            val chunkSteps = when {
                isFirst -> 1
                isBridgeClause -> minOf(qualityMode.steps, 3)
                else -> qualityMode.steps
            }
            val chunkAudio = inferenceMutex.withLock {
                currentCoroutineContext().ensureActive()
                val pcm = synthesizeChunkWithFallback(
                    clause,
                    sid,
                    speed,
                    chunkSteps,
                    isClause = isIntraSentenceClause,
                    isInitialMicroChunk = isFirst
                )
                warmedUp = true
                pcm
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
        sid: Int,
        speed: Float,
        steps: Int,
        isClause: Boolean = false,
        isInitialMicroChunk: Boolean = false
    ): ShortArray {
        if (isClosed) return ShortArray(0)
        return if (activeProvider == "NNAPI") {
            try {
                synthesizeChunk(chunk, sid, speed, steps, isClause = isClause, isInitialMicroChunk = isInitialMicroChunk)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fallbackToCpu()
                synthesizeChunk(chunk, sid, speed, steps, isClause = isClause, isInitialMicroChunk = isInitialMicroChunk)
            }
        } else {
            synthesizeChunk(chunk, sid, speed, steps, isClause = isClause, isInitialMicroChunk = isInitialMicroChunk)
        }
    }

    private suspend fun synthesizeChunk(
        chunk: String,
        sid: Int,
        speed: Float,
        steps: Int,
        isClause: Boolean = false,
        isInitialMicroChunk: Boolean = false
    ): ShortArray {
        if (isClosed) return ShortArray(0)
        currentCoroutineContext().ensureActive()

        val preprocessed = frontend.preprocess(chunk, "en", isClause = isClause)
        if (preprocessed.isEmpty()) return ShortArray(0)
        val (tokens, mask) = frontend.encode(preprocessed)
        val seqLen = tokens.size.toLong()

        val dpSess = dpSession ?: return ShortArray(0)
        val textEncSess = textEncSession ?: return ShortArray(0)
        val vectorEstSess = vectorEstSession ?: return ShortArray(0)
        val vocoderSess = vocoderSession ?: return ShortArray(0)
        val inputs = cachedInputs ?: run { resolveInputNames(); cachedInputs!! }
        val voiceTensors = getVoiceTensors(sid)

        val textIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, seqLen))
        val textMaskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, seqLen))

        // 1. Duration Predictor
        val dpInputs = mapOf(
            inputs.dpTextIds to textIdsTensor,
            inputs.dpStyleDp to voiceTensors.dpTensor,
            inputs.dpTextMask to textMaskTensor,
        )
        val durSeconds: Float
        dpSess.run(dpInputs).use { out ->
            val tensor = out.get(0) as OnnxTensor
            durSeconds = tensor.floatBuffer.get(0)
        }

        val rawAdjustedDur = (if (speed > 0f) durSeconds / speed else durSeconds).coerceAtLeast(0.1f)
        val adjustedDur = if (isInitialMicroChunk) {
            val microCap = (chunk.length * 0.048f / speed.coerceAtLeast(0.5f)).coerceIn(0.55f, 1.25f)
            minOf(rawAdjustedDur, microCap)
        } else {
            rawAdjustedDur
        }
        val wavLen = (adjustedDur * sampleRate).toInt().coerceAtLeast(1)
        val wavChunkSize = baseChunkSize * chunkCompressFactor
        val latentLen = ((wavLen + wavChunkSize - 1) / wavChunkSize).coerceIn(1, 10000)
        val latentDimTotal = latentDim * chunkCompressFactor
        val latentTotalElements = latentDimTotal * latentLen

        // 2. Text Encoder
        val teInputs = mapOf(
            inputs.teTextIds to textIdsTensor,
            inputs.teStyleTtl to voiceTensors.ttlTensor,
            inputs.teTextMask to textMaskTensor,
        )
        val textEncResult = textEncSess.run(teInputs)
        val textEmbTensor = textEncResult.get(0) as OnnxTensor
        textIdsTensor.close()

        // 3. Vector Estimator (parameterized flow matching steps: 1, 2, 3, or 5)
        val xtFlat = FloatArray(latentTotalElements)
        val copyCount = minOf(latentTotalElements, gaussianNoiseCache.size)
        System.arraycopy(gaussianNoiseCache, 0, xtFlat, 0, copyCount)
        if (copyCount < latentTotalElements) {
            val rng = Random(42L)
            for (i in copyCount until latentTotalElements) {
                xtFlat[i] = (rng.nextGaussian() * 0.5f).toFloat()
            }
        }
        val latentMaskArray = FloatArray(latentLen) { 1.0f }
        val numSteps = steps.coerceAtLeast(1)
        val cachedTotalTensor = totalStepTensors?.getOrNull(numSteps)
        val totalStepTensor = cachedTotalTensor
            ?: OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(numSteps.toFloat())), longArrayOf(1))
        val latentMaskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(latentMaskArray), longArrayOf(1, 1, latentLen.toLong()))

        for (step in 0 until numSteps) {
            currentCoroutineContext().ensureActive()
            val noisyLatentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(xtFlat), longArrayOf(1, latentDimTotal.toLong(), latentLen.toLong()))
            val cachedStepTensor = stepTensors?.getOrNull(step)
            val currentStepTensor = cachedStepTensor
                ?: OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1))

            val veInputs = mapOf(
                inputs.veNoisyLatent to noisyLatentTensor,
                inputs.veCurrentStep to currentStepTensor,
                inputs.veTextEmb to textEmbTensor,
                inputs.veStyleTtl to voiceTensors.ttlTensor,
                inputs.veLatentMask to latentMaskTensor,
                inputs.veTextMask to textMaskTensor,
                inputs.veTotalStep to totalStepTensor,
            )

            vectorEstSess.run(veInputs).use { out ->
                val denoised = out.get(0) as OnnxTensor
                denoised.floatBuffer.get(xtFlat)
            }
            noisyLatentTensor.close()
            if (cachedStepTensor == null) {
                currentStepTensor.close()
            }
        }
        textEmbTensor.close()
        textEncResult.close()
        if (cachedTotalTensor == null) {
            totalStepTensor.close()
        }
        latentMaskTensor.close()
        textMaskTensor.close()

        // 4. Vocoder
        currentCoroutineContext().ensureActive()
        val finalLatentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(xtFlat), longArrayOf(1, latentDimTotal.toLong(), latentLen.toLong()))
        val vocInputs = mapOf(inputs.vocoderInput to finalLatentTensor)
        val pcm16: ShortArray
        vocoderSess.run(vocInputs).use { out ->
            val wavTensor = out.get(0) as OnnxTensor
            val wavBuf = wavTensor.floatBuffer
            val totalSamples = wavBuf.remaining()
            val sampleCount = minOf(wavLen, totalSamples)
            val floatBuf = FloatArray(sampleCount)
            wavBuf.get(floatBuf, 0, sampleCount)
            pcm16 = ShortArray(sampleCount)
            for (i in 0 until sampleCount) {
                val s = floatBuf[i].coerceIn(-1.0f, 1.0f)
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
        val voiceTensors = getVoiceTensors(sid)

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
        val inputs = cachedInputs ?: run { resolveInputNames(); cachedInputs!! }

        val textIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, seqLen))
        val textMaskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, seqLen))

        // 1. Duration Predictor
        val tDp0 = System.nanoTime()
        val dpInputs = mapOf(
            inputs.dpTextIds to textIdsTensor,
            inputs.dpStyleDp to voiceTensors.dpTensor,
            inputs.dpTextMask to textMaskTensor,
        )
        val durSeconds: Float
        dpSess.run(dpInputs).use { out ->
            val tensor = out.get(0) as OnnxTensor
            durSeconds = tensor.floatBuffer.get(0)
        }
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
        val teInputs = mapOf(
            inputs.teTextIds to textIdsTensor,
            inputs.teStyleTtl to voiceTensors.ttlTensor,
            inputs.teTextMask to textMaskTensor,
        )
        val textEncResult = textEncSess.run(teInputs)
        val textEmbTensor = textEncResult.get(0) as OnnxTensor
        textIdsTensor.close()
        val tTe1 = System.nanoTime()
        val teMs = (tTe1 - tTe0) / 1_000_000.0

        // 3. Vector Estimator
        val tVe0 = System.nanoTime()
        val xtFlat = FloatArray(latentTotalElements)
        val copyCount = minOf(latentTotalElements, gaussianNoiseCache.size)
        System.arraycopy(gaussianNoiseCache, 0, xtFlat, 0, copyCount)
        if (copyCount < latentTotalElements) {
            val rng = Random(42L)
            for (i in copyCount until latentTotalElements) {
                xtFlat[i] = (rng.nextGaussian() * 0.5f).toFloat()
            }
        }
        val latentMaskArray = FloatArray(latentLen) { 1.0f }
        val numSteps = qualityMode.steps
        val cachedTotalTensor = totalStepTensors?.getOrNull(numSteps)
        val totalStepTensor = cachedTotalTensor
            ?: OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(numSteps.toFloat())), longArrayOf(1))
        val latentMaskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(latentMaskArray), longArrayOf(1, 1, latentLen.toLong()))

        for (step in 0 until numSteps) {
            val noisyLatentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(xtFlat), longArrayOf(1, latentDimTotal.toLong(), latentLen.toLong()))
            val cachedStepTensor = stepTensors?.getOrNull(step)
            val currentStepTensor = cachedStepTensor
                ?: OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1))

            val veInputs = mapOf(
                inputs.veNoisyLatent to noisyLatentTensor,
                inputs.veCurrentStep to currentStepTensor,
                inputs.veTextEmb to textEmbTensor,
                inputs.veStyleTtl to voiceTensors.ttlTensor,
                inputs.veLatentMask to latentMaskTensor,
                inputs.veTextMask to textMaskTensor,
                inputs.veTotalStep to totalStepTensor,
            )

            vectorEstSess.run(veInputs).use { out ->
                val denoised = out.get(0) as OnnxTensor
                denoised.floatBuffer.get(xtFlat)
            }
            noisyLatentTensor.close()
            if (cachedStepTensor == null) {
                currentStepTensor.close()
            }
        }
        textEmbTensor.close()
        textEncResult.close()
        if (cachedTotalTensor == null) {
            totalStepTensor.close()
        }
        latentMaskTensor.close()
        textMaskTensor.close()
        val tVe1 = System.nanoTime()
        val veMs = (tVe1 - tVe0) / 1_000_000.0

        // 4. Vocoder
        val tVoc0 = System.nanoTime()
        val finalLatentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(xtFlat), longArrayOf(1, latentDimTotal.toLong(), latentLen.toLong()))
        val vocInputs = mapOf(inputs.vocoderInput to finalLatentTensor)
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
        closeConstantTensors()
        voiceTensorCache.values.forEach { it.close() }
        voiceTensorCache.clear()
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
        cachedInputs = null
    }
}
