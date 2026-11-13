package com.anchorreader.supertonic

import com.anchorreader.HdPrefetchPipeline
import com.anchorreader.data.Book
import com.anchorreader.data.Concept
import com.anchorreader.data.Progress
import com.anchorreader.upcomingSentences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NarratorPrefetchPipelineTest {

    @Test
    fun testSpeakFollowedImmediatelyByPrepareNextNeverEvictsOrCancelsEitherSentence() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val synthesizedOrder = Collections.synchronizedList(mutableListOf<String>())
            val pipeline = HdPrefetchPipeline(
                scope = scope,
                dispatcher = Dispatchers.Default,
                maxEntries = 4,
            ) { text, _, _, _ ->
                flow {
                    synthesizedOrder.add("$text#0")
                    delay(15)
                    emit(shortArrayOf(1, 2, 3))
                    synthesizedOrder.add("$text#1")
                    delay(15)
                    emit(shortArrayOf(4, 5, 6))
                }
            }

            // Simulate speakCurrent(): claim sentence_0 synchronously, then immediately enqueue lookahead [sentence_1, sentence_2]
            val entry0 = pipeline.claimForPlayback("Sentence zero.", "F1", 1.0f, SupertonicQualityMode.STUDIO_5_STEP)
            pipeline.enqueueLookahead(
                listOf("Sentence one.", "Sentence two."),
                "F1",
                1.0f,
                SupertonicQualityMode.STUDIO_5_STEP
            )

            // Collect sentence_0 streaming chunks
            val chunks0 = withTimeout(2_000) { entry0.asFlow().toList() }
            assertEquals(2, chunks0.size)
            assertArrayEquals(shortArrayOf(1, 2, 3), chunks0[0])
            assertArrayEquals(shortArrayOf(4, 5, 6), chunks0[1])
            assertTrue(entry0.completed)

            // Wait for background lookahead of sentence_1 and sentence_2 to complete
            val entry1 = pipeline.claimForPlayback("Sentence one.", "F1", 1.0f, SupertonicQualityMode.STUDIO_5_STEP)
            val chunks1 = withTimeout(2_000) { entry1.asFlow().toList() }
            assertEquals(2, chunks1.size)

            val entry2 = pipeline.claimForPlayback("Sentence two.", "F1", 1.0f, SupertonicQualityMode.STUDIO_5_STEP)
            val chunks2 = withTimeout(2_000) { entry2.asFlow().toList() }
            assertEquals(2, chunks2.size)

            // Verify strict priority ordering: all clauses of Sentence zero completed before Sentence one and two
            assertEquals(
                listOf(
                    "Sentence zero.#0",
                    "Sentence zero.#1",
                    "Sentence one.#0",
                    "Sentence one.#1",
                    "Sentence two.#0",
                    "Sentence two.#1",
                ),
                synthesizedOrder.toList()
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testPausePreservesPrewarmedCacheForInstantZeroLatencyPlay() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            var synthesisCalls = 0
            val pipeline = HdPrefetchPipeline(
                scope = scope,
                dispatcher = Dispatchers.Default,
                maxEntries = 4,
            ) { text, _, _, _ ->
                flow {
                    synthesisCalls++
                    emit(shortArrayOf(text.length.toShort()))
                }
            }

            // Pre-warm sentence 0 and sentence 1 while on Overview/Player screen
            pipeline.enqueueLookahead(
                listOf("First sentence.", "Second sentence."),
                "F1",
                1.0f,
                SupertonicQualityMode.STUDIO_5_STEP
            )

            // Wait until both are completed in cache
            val key0 = HdPrefetchPipeline.buildCacheKey("F1", 1.0f, SupertonicQualityMode.STUDIO_5_STEP, "First sentence.")
            val key1 = HdPrefetchPipeline.buildCacheKey("F1", 1.0f, SupertonicQualityMode.STUDIO_5_STEP, "Second sentence.")
            withTimeout(2_000) {
                while (pipeline.getEntryForTest(key0)?.completed != true ||
                    pipeline.getEntryForTest(key1)?.completed != true
                ) {
                    delay(5)
                }
            }
            assertEquals(2, synthesisCalls)

            // Simulate pauseAnd { model.start(...) } which calls clearActivePlayback(keepInLookahead = true)
            pipeline.clearActivePlayback(keepInLookahead = true)

            // Claim sentence 0 for playback and measure cache-hit latency
            val t0 = System.nanoTime()
            val entry0 = pipeline.claimForPlayback("First sentence.", "F1", 1.0f, SupertonicQualityMode.STUDIO_5_STEP)
            val firstChunk = entry0.asFlow().first()
            val hitMs = (System.nanoTime() - t0) / 1_000_000.0

            assertEquals(1, firstChunk.size)
            assertTrue("Pre-warmed cache hit must be < 5 ms, was ${hitMs}ms", hitMs < 5.0)
            assertEquals("Must not re-synthesize cached sentences", 2, synthesisCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testActivePlaybackPreemptsInFlightLookaheadAndResumesWithoutDuplicateClauses() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val clause0Started = CountDownLatch(1)
            val pipeline = HdPrefetchPipeline(
                scope = scope,
                dispatcher = Dispatchers.Default,
                maxEntries = 4,
            ) { text, _, _, _ ->
                flow {
                    if (text == "Lookahead sentence.") {
                        emit(shortArrayOf(10))
                        clause0Started.countDown()
                        delay(200)
                        emit(shortArrayOf(20))
                    } else {
                        emit(shortArrayOf(99))
                    }
                }
            }

            // Start background lookahead
            pipeline.enqueueLookahead(listOf("Lookahead sentence."), "F1", 1.0f, SupertonicQualityMode.FAST_3_STEP)
            assertTrue(clause0Started.await(2, TimeUnit.SECONDS))

            // While lookahead is mid-synthesis on clause 1, user claims an urgent uncached sentence
            val urgentEntry = pipeline.claimForPlayback("Urgent sentence.", "F1", 1.0f, SupertonicQualityMode.FAST_3_STEP)
            val urgentChunks = withTimeout(1_000) { urgentEntry.asFlow().toList() }
            assertEquals(1, urgentChunks.size)
            assertArrayEquals(shortArrayOf(99), urgentChunks[0])

            // Afterward, lookahead sentence finishes cleanly without duplicating clause 0
            val lookaheadEntry = pipeline.claimForPlayback("Lookahead sentence.", "F1", 1.0f, SupertonicQualityMode.FAST_3_STEP)
            val lookaheadChunks = withTimeout(2_000) { lookaheadEntry.asFlow().toList() }
            assertEquals(2, lookaheadChunks.size)
            assertArrayEquals(shortArrayOf(10), lookaheadChunks[0])
            assertArrayEquals(shortArrayOf(20), lookaheadChunks[1])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testInvalidateAllClearsCacheOnVoiceSpeedOrQualityChange() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val pipeline = HdPrefetchPipeline(
                scope = scope,
                dispatcher = Dispatchers.Default,
                maxEntries = 4,
            ) { _, _, _, _ ->
                flow { emit(shortArrayOf(1, 2)) }
            }

            val entry = pipeline.claimForPlayback("Cached text.", "F1", 1.0f, SupertonicQualityMode.STUDIO_5_STEP)
            withTimeout(1_000) { entry.asFlow().toList() }
            assertTrue(pipeline.cachedKeysForTest().isNotEmpty())

            pipeline.invalidateAll()
            assertTrue(pipeline.cachedKeysForTest().isEmpty())
            assertNull(pipeline.getEntryForTest(entry.key))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testUpcomingSentencesCrossesConceptBoundariesAndHandlesCompletedProgress() {
        val book = Book(
            id = "b1",
            title = "Test",
            author = "Author",
            units = listOf(
                Concept(title = "Unit 1", sentences = listOf("U0 S0.", "U0 S1.")),
                Concept(title = "Unit 2", sentences = listOf("U1 S0.", "U1 S1."))
            ),
            sourceText = "U0 S0. U0 S1. U1 S0. U1 S1."
        )

        // From (unit 0, sentence 1), upcoming 3 sentences should include U0 S1, U1 S0, U1 S1
        val fromU0S1 = upcomingSentences(book, Progress(unitIndex = 0, sentenceIndex = 1), count = 3)
        assertEquals(listOf("U0 S1.", "U1 S0.", "U1 S1."), fromU0S1)

        // When book progress is marked completed, start() resets to (0, 0), so upcomingSentences must start at U0 S0
        val fromCompleted = upcomingSentences(book, Progress(unitIndex = 1, sentenceIndex = 1, completed = true), count = 2)
        assertEquals(listOf("U0 S0.", "U0 S1."), fromCompleted)
    }
}
