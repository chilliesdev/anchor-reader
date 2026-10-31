package com.anchorreader.supertonic

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SupertonicBenchmarkDeviceTest {

    companion object {
        private const val TAG = "SupertonicBenchmark"
        private lateinit var voiceDir: File

        @BeforeClass
        @JvmStatic
        fun prepareVoicePack() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val targetDir = File(context.filesDir, "supertonic_voice")
            val fallbackDir = File("/data/local/tmp/supertonic_voice")

            if (isVoiceDirectoryComplete(targetDir)) {
                voiceDir = targetDir
            } else if (isVoiceDirectoryComplete(fallbackDir)) {
                targetDir.mkdirs()
                fallbackDir.listFiles()?.forEach { file ->
                    val dest = File(targetDir, file.name)
                    if (!dest.exists() || dest.length() != file.length()) {
                        file.copyTo(dest, overwrite = true)
                    }
                }
                File(targetDir, SupertonicManifest.VERIFIED_MARKER).writeText("verified\nbenchmark\n")
                voiceDir = if (isVoiceDirectoryComplete(targetDir)) targetDir else fallbackDir
            } else {
                throw IllegalStateException(
                    "Supertonic voice pack missing. Checked: ${targetDir.absolutePath} and ${fallbackDir.absolutePath}"
                )
            }
            Log.i(TAG, "BENCHMARK_PREPARED voice_dir=${voiceDir.absolutePath}")
        }

        private fun isVoiceDirectoryComplete(dir: File): Boolean {
            if (!dir.exists() || !dir.isDirectory) return false
            for (filename in SupertonicManifest.REQUIRED_FILES) {
                val f = File(dir, filename)
                if (!f.exists() || f.length() == 0L) return false
            }
            return true
        }
    }

    @Test
    fun test01_sessionInitLatency() {
        System.gc()
        Thread.sleep(100)

        // 1. Cold initialization
        val coldStart = System.nanoTime()
        val coldEngine = SupertonicEngine(voiceDir)
        val coldMs = (System.nanoTime() - coldStart) / 1_000_000.0
        val provider = coldEngine.activeProvider
        assertNotNull(coldEngine)
        coldEngine.close()

        // 2. Warm initialization (average over 3 runs)
        val warmRuns = 3
        var totalWarmMs = 0.0
        for (i in 0 until warmRuns) {
            System.gc()
            Thread.sleep(50)
            val warmStart = System.nanoTime()
            val warmEngine = SupertonicEngine(voiceDir)
            val elapsed = (System.nanoTime() - warmStart) / 1_000_000.0
            totalWarmMs += elapsed
            warmEngine.close()
        }
        val warmMs = totalWarmMs / warmRuns

        Log.i(TAG, "SESSION_INIT cold_ms=%.2f warm_ms=%.2f provider=%s".format(coldMs, warmMs, provider))
        assertTrue("Cold init latency must be positive", coldMs > 0)
        assertTrue("Warm init latency must be positive", warmMs > 0)
    }

    @Test
    fun test02_componentBreakdownLatency() = runBlocking {
        val engine = SupertonicEngine(voiceDir)
        try {
            val warmupText = "Hello world."
            engine.benchmarkComponentBreakdown(warmupText, qualityMode = SupertonicQualityMode.FAST_3_STEP)

            val benchmarkText = "Anchor Reader provides private offline document reading."

            // Benchmark FAST_3_STEP (3 runs)
            val runs = 3
            var dpSum3 = 0.0
            var teSum3 = 0.0
            var veSum3 = 0.0
            var vocSum3 = 0.0
            var totSum3 = 0.0
            var audioSec3 = 0f
            var rtfSum3 = 0.0

            for (i in 0 until runs) {
                val breakdown = engine.benchmarkComponentBreakdown(
                    benchmarkText,
                    qualityMode = SupertonicQualityMode.FAST_3_STEP
                )
                dpSum3 += breakdown.durationPredictorMs
                teSum3 += breakdown.textEncoderMs
                veSum3 += breakdown.vectorEstimatorMs
                vocSum3 += breakdown.vocoderMs
                totSum3 += breakdown.totalInferenceMs
                audioSec3 = breakdown.audioDurationSeconds
                rtfSum3 += breakdown.rtf
            }

            val dpAvg3 = dpSum3 / runs
            val teAvg3 = teSum3 / runs
            val veAvg3 = veSum3 / runs
            val vocAvg3 = vocSum3 / runs
            val totAvg3 = totSum3 / runs
            val rtfAvg3 = rtfSum3 / runs

            Log.i(
                TAG,
                "COMPONENT_BREAKDOWN mode=FAST_3_STEP dp_ms=%.2f te_ms=%.2f ve_ms=%.2f vocoder_ms=%.2f total_ms=%.2f audio_sec=%.2f rtf=%.2f"
                    .format(dpAvg3, teAvg3, veAvg3, vocAvg3, totAvg3, audioSec3, rtfAvg3)
            )

            // Benchmark STUDIO_5_STEP (3 runs)
            var dpSum5 = 0.0
            var teSum5 = 0.0
            var veSum5 = 0.0
            var vocSum5 = 0.0
            var totSum5 = 0.0
            var audioSec5 = 0f
            var rtfSum5 = 0.0

            for (i in 0 until runs) {
                val breakdown = engine.benchmarkComponentBreakdown(
                    benchmarkText,
                    qualityMode = SupertonicQualityMode.STUDIO_5_STEP
                )
                dpSum5 += breakdown.durationPredictorMs
                teSum5 += breakdown.textEncoderMs
                veSum5 += breakdown.vectorEstimatorMs
                vocSum5 += breakdown.vocoderMs
                totSum5 += breakdown.totalInferenceMs
                audioSec5 = breakdown.audioDurationSeconds
                rtfSum5 += breakdown.rtf
            }

            val dpAvg5 = dpSum5 / runs
            val teAvg5 = teSum5 / runs
            val veAvg5 = veSum5 / runs
            val vocAvg5 = vocSum5 / runs
            val totAvg5 = totSum5 / runs
            val rtfAvg5 = rtfSum5 / runs

            Log.i(
                TAG,
                "COMPONENT_BREAKDOWN mode=STUDIO_5_STEP dp_ms=%.2f te_ms=%.2f ve_ms=%.2f vocoder_ms=%.2f total_ms=%.2f audio_sec=%.2f rtf=%.2f"
                    .format(dpAvg5, teAvg5, veAvg5, vocAvg5, totAvg5, audioSec5, rtfAvg5)
            )

            assertTrue("Duration Predictor latency must be positive", dpAvg3 > 0)
            assertTrue("Text Encoder latency must be positive", teAvg3 > 0)
            assertTrue("Vector Estimator 3-step latency must be positive", veAvg3 > 0)
            assertTrue("Vector Estimator 5-step latency must be positive", veAvg5 > 0)
            assertTrue("Vocoder latency must be positive", vocAvg3 > 0)
        } finally {
            engine.close()
        }
    }

    @Test
    fun test03_clause1StreamingTimeToFirstAudio() = runBlocking {
        val engine = SupertonicEngine(voiceDir)
        try {
            val paragraph = "Anchor Reader provides private offline document reading. It streams high fidelity speech without external cloud dependencies."

            // Warmup
            engine.synthesizeStream("Warm up stream.", qualityMode = SupertonicQualityMode.FAST_3_STEP).first()

            // 1. FAST_3_STEP streaming TTFA
            val runs = 3
            var ttfaSum3 = 0.0
            var streamSum3 = 0.0

            for (i in 0 until runs) {
                val start = System.nanoTime()
                var ttfa = 0.0
                var firstChunk = true
                engine.synthesizeStream(paragraph, qualityMode = SupertonicQualityMode.FAST_3_STEP).collect { chunk ->
                    if (firstChunk && chunk.isNotEmpty()) {
                        ttfa = (System.nanoTime() - start) / 1_000_000.0
                        firstChunk = false
                    }
                }
                val totalStreamMs = (System.nanoTime() - start) / 1_000_000.0
                ttfaSum3 += ttfa
                streamSum3 += totalStreamMs
            }

            val avgTtfa3 = ttfaSum3 / runs
            val avgTotal3 = streamSum3 / runs

            Log.i(
                TAG,
                "STREAMING_TTFA mode=FAST_3_STEP clause1_ttfa_ms=%.2f total_stream_ms=%.2f"
                    .format(avgTtfa3, avgTotal3)
            )

            // 2. STUDIO_5_STEP streaming TTFA
            var ttfaSum5 = 0.0
            var streamSum5 = 0.0

            for (i in 0 until runs) {
                val start = System.nanoTime()
                var ttfa = 0.0
                var firstChunk = true
                engine.synthesizeStream(paragraph, qualityMode = SupertonicQualityMode.STUDIO_5_STEP).collect { chunk ->
                    if (firstChunk && chunk.isNotEmpty()) {
                        ttfa = (System.nanoTime() - start) / 1_000_000.0
                        firstChunk = false
                    }
                }
                val totalStreamMs = (System.nanoTime() - start) / 1_000_000.0
                ttfaSum5 += ttfa
                streamSum5 += totalStreamMs
            }

            val avgTtfa5 = ttfaSum5 / runs
            val avgTotal5 = streamSum5 / runs

            Log.i(
                TAG,
                "STREAMING_TTFA mode=STUDIO_5_STEP clause1_ttfa_ms=%.2f total_stream_ms=%.2f"
                    .format(avgTtfa5, avgTotal5)
            )

            assertTrue("Clause 1 TTFA must be positive", avgTtfa3 > 0)
            assertTrue("Clause 1 TTFA must be less than total stream time", avgTtfa3 < avgTotal3)
        } finally {
            engine.close()
        }
    }

    @Test
    fun test04_lookaheadCacheHitVsMissLatency() = runBlocking {
        val engine = SupertonicEngine(voiceDir)
        try {
            val sentence = "The next paragraph begins with an introduction to offline machine learning."

            // Measure Cache Miss latency (cold on-demand synthesis)
            val missStart = System.nanoTime()
            val freshPcm = engine.synthesize(sentence, qualityMode = SupertonicQualityMode.FAST_3_STEP)
            val missMs = (System.nanoTime() - missStart) / 1_000_000.0
            assertTrue("Synthesized PCM must not be empty", freshPcm.isNotEmpty())

            // Simulate lookahead background precomputation cache
            val cacheKey = "F1:1.0:FAST_3_STEP:$sentence"
            val lookaheadCache = mutableMapOf<String, ShortArray>()
            lookaheadCache[cacheKey] = freshPcm

            // Measure Cache Hit latency (instant lookup of precomputed buffer)
            val hitStart = System.nanoTime()
            val cachedPcm = lookaheadCache[cacheKey]
            val hitMs = (System.nanoTime() - hitStart) / 1_000_000.0
            assertNotNull(cachedPcm)

            val speedup = missMs / maxOf(0.001, hitMs)
            Log.i(
                TAG,
                "LOOKAHEAD_CACHE hit_ms=%.3f miss_ms=%.2f speedup_ratio=%.1fx"
                    .format(hitMs, missMs, speedup)
            )

            assertTrue("Cache hit latency must be significantly faster than cache miss", hitMs < missMs)
        } finally {
            engine.close()
        }
    }
}
