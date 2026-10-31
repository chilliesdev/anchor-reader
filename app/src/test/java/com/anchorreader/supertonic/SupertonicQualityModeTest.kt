package com.anchorreader.supertonic

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SupertonicQualityModeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun testQualityModeEnumValuesAndSteps() {
        assertEquals(5, SupertonicQualityMode.STUDIO_5_STEP.steps)
        assertEquals("Studio (5 steps)", SupertonicQualityMode.STUDIO_5_STEP.title)
        assertEquals("Full reference fidelity.", SupertonicQualityMode.STUDIO_5_STEP.subtitle)

        assertEquals(3, SupertonicQualityMode.FAST_3_STEP.steps)
        assertEquals("Fast (3 steps)", SupertonicQualityMode.FAST_3_STEP.title)
        assertEquals("Faster synthesis with near-studio quality.", SupertonicQualityMode.FAST_3_STEP.subtitle)

        assertEquals(SupertonicQualityMode.STUDIO_5_STEP, SupertonicQualityMode.fromName("STUDIO_5_STEP"))
        assertEquals(SupertonicQualityMode.FAST_3_STEP, SupertonicQualityMode.fromName("FAST_3_STEP"))
        assertEquals(SupertonicQualityMode.STUDIO_5_STEP, SupertonicQualityMode.fromName(null))
        assertEquals(SupertonicQualityMode.STUDIO_5_STEP, SupertonicQualityMode.fromName("UNKNOWN"))
    }

    @Test
    fun testAdaptiveCpuThreadingFormula() {
        assertEquals(2, SupertonicEngine.defaultIntraOpThreads(cores = 1))
        assertEquals(2, SupertonicEngine.defaultIntraOpThreads(cores = 2))
        assertEquals(2, SupertonicEngine.defaultIntraOpThreads(cores = 3))
        assertEquals(2, SupertonicEngine.defaultIntraOpThreads(cores = 4))
        assertEquals(2, SupertonicEngine.defaultIntraOpThreads(cores = 5))
        assertEquals(3, SupertonicEngine.defaultIntraOpThreads(cores = 6))
        assertEquals(3, SupertonicEngine.defaultIntraOpThreads(cores = 7))
        assertEquals(4, SupertonicEngine.defaultIntraOpThreads(cores = 8))
        assertEquals(4, SupertonicEngine.defaultIntraOpThreads(cores = 12))
        assertEquals(4, SupertonicEngine.defaultIntraOpThreads(cores = 16))
    }

    @Test
    fun testSupertonicQualityStorePersistence() = runBlocking {
        val testFile = tmp.newFile("test_quality_prefs.preferences_pb")
        val store = SupertonicQualityStore.createForTest(testFile)

        // Default should be STUDIO_5_STEP
        assertEquals(SupertonicQualityMode.STUDIO_5_STEP, store.qualityMode.first())

        // Update to FAST_3_STEP
        store.setQualityMode(SupertonicQualityMode.FAST_3_STEP)
        assertEquals(SupertonicQualityMode.FAST_3_STEP, store.qualityMode.first())
        store.closeForTest()

        // Reopen same file in a new store instance to verify disk persistence
        val reloadedStore = SupertonicQualityStore.createForTest(testFile)
        assertEquals(SupertonicQualityMode.FAST_3_STEP, reloadedStore.qualityMode.first())

        // Update back to STUDIO_5_STEP
        reloadedStore.setQualityMode(SupertonicQualityMode.STUDIO_5_STEP)
        assertEquals(SupertonicQualityMode.STUDIO_5_STEP, reloadedStore.qualityMode.first())
        reloadedStore.closeForTest()
    }
}
