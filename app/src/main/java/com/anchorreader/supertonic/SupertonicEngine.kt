package com.anchorreader.supertonic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random

class SupertonicEngine(
    private val voiceDir: File,
    private val intraOpThreads: Int = 2,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(intraOpThreads)
        setInterOpNumThreads(1)
        setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
    }

    val sampleRate: Int
    val baseChunkSize: Int
    val chunkCompressFactor: Int
    val latentDim: Int

    val voiceParser: SupertonicVoiceParser
    val frontend: SupertonicTextFrontend

    private val dpSession: OrtSession
    private val textEncSession: OrtSession
    private val vectorEstSession: OrtSession
    private val vocoderSession: OrtSession

    private val random = Random(42)

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

        dpSession = env.createSession(File(voiceDir, "duration_predictor.int8.onnx").absolutePath, sessionOptions)
        textEncSession = env.createSession(File(voiceDir, "text_encoder.int8.onnx").absolutePath, sessionOptions)
        vectorEstSession = env.createSession(File(voiceDir, "vector_estimator.int8.onnx").absolutePath, sessionOptions)
        vocoderSession = env.createSession(File(voiceDir, "vocoder.int8.onnx").absolutePath, sessionOptions)
    }

    fun synthesize(text: String, voiceName: String = SupertonicManifest.DEFAULT_VOICE, speed: Float = 1.0f): ShortArray {
        val clauses = SupertonicTextFrontend.splitClauses(text)
        if (clauses.isEmpty()) return ShortArray(0)

        val sid = SupertonicVoiceParser.sidForVoice(voiceName)
        val slice = voiceParser.getVoiceSlice(sid)

        val buffers = mutableListOf<ShortArray>()
        for (clause in clauses) {
            val audio = synthesizeChunk(clause, slice, speed)
            if (audio.isNotEmpty()) {
                buffers.add(audio)
            }
        }
        val totalLen = buffers.sumOf { it.size }
        val result = ShortArray(totalLen)
        var offset = 0
        for (buf in buffers) {
            System.arraycopy(buf, 0, result, offset, buf.size)
            offset += buf.size
        }
        return result
    }

    private fun synthesizeChunk(chunk: String, slice: SupertonicVoiceParser.VoiceStyleSlice, speed: Float): ShortArray {
        val preprocessed = frontend.preprocess(chunk, "en")
        if (preprocessed.isEmpty()) return ShortArray(0)
        val (tokens, mask) = frontend.encode(preprocessed)
        val seqLen = tokens.size.toLong()

        // 1. Duration Predictor
        val dpTextIds = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, seqLen))
        val dpStyleDp = OnnxTensor.createTensor(env, FloatBuffer.wrap(slice.dpData), slice.dpShape)
        val dpTextMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, seqLen))

        val dpInputs = mapOf(
            findInput(dpSession, "text", "id") to dpTextIds,
            findInput(dpSession, "style", "dp") to dpStyleDp,
            findInput(dpSession, "mask") to dpTextMask,
        )
        val durSeconds: Float
        dpSession.run(dpInputs).use { out ->
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
            findInput(textEncSession, "text", "id") to teTextIds,
            findInput(textEncSession, "style", "ttl") to teStyleTtl,
            findInput(textEncSession, "mask") to teTextMask,
        )
        val textEmbTensor: OnnxTensor
        val textEncResult = textEncSession.run(teInputs)
        textEmbTensor = textEncResult.get(0) as OnnxTensor
        teTextIds.close(); teStyleTtl.close(); teTextMask.close()

        // 3. Vector Estimator (5 flow matching steps)
        val xtFlat = FloatArray(latentTotalElements) { (random.nextGaussian() * 0.5f).toFloat() }
        val latentMaskArray = FloatArray(latentLen) { 1.0f }
        val numSteps = 5
        val totalStepTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(numSteps.toFloat())), longArrayOf(1))
        val latentMaskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(latentMaskArray), longArrayOf(1, 1, latentLen.toLong()))
        val veStyleTtl = OnnxTensor.createTensor(env, FloatBuffer.wrap(slice.ttlData), slice.ttlShape)
        val veTextMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, seqLen))

        for (step in 0 until numSteps) {
            val noisyLatentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(xtFlat), longArrayOf(1, latentDimTotal.toLong(), latentLen.toLong()))
            val currentStepTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1))

            val veInputs = mapOf(
                findInput(vectorEstSession, "noisy", "latent", "x") to noisyLatentTensor,
                findInput(vectorEstSession, "current", "step") to currentStepTensor,
                findInput(vectorEstSession, "text_emb", "emb") to textEmbTensor,
                findInput(vectorEstSession, "style", "ttl") to veStyleTtl,
                findInput(vectorEstSession, "latent_mask") to latentMaskTensor,
                findInput(vectorEstSession, "text_mask") to veTextMask,
                findInput(vectorEstSession, "total") to totalStepTensor,
            )

            vectorEstSession.run(veInputs).use { out ->
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
        val finalLatentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(xtFlat), longArrayOf(1, latentDimTotal.toLong(), latentLen.toLong()))
        val vocInputs = mapOf(vocoderSession.inputNames.first() to finalLatentTensor)
        val pcm16: ShortArray
        vocoderSession.run(vocInputs).use { out ->
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
        dpSession.close()
        textEncSession.close()
        vectorEstSession.close()
        vocoderSession.close()
        sessionOptions.close()
        env.close()
    }
}
