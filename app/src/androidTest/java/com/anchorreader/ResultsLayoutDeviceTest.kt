package com.anchorreader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anchorreader.data.Outcome
import com.anchorreader.data.Progress
import com.anchorreader.data.SampleLesson
import com.anchorreader.ui.AnchorTheme
import com.anchorreader.ui.FinishScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResultsLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val captions = listOf("concepts visited", "checks correct", "checks skipped")

    @Test
    fun narrowLargeTextSummaryKeepsWholeWordsAndAllCounts() {
        showResults(320, 2f)
        captions.forEach { caption ->
            val node = compose.onNodeWithText(caption).performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            for (line in 0 until layout.lineCount) {
                val start = layout.getLineStart(line)
                val end = layout.getLineEnd(line)
                assertTrue("Caption starts inside a word: $caption", start == 0 || caption[start - 1].isWhitespace() || caption[start].isWhitespace())
                assertTrue("Caption ends inside a word: $caption", end == caption.length || caption[end - 1].isWhitespace() || caption[end].isWhitespace())
            }
        }
        compose.onNodeWithText("3 / 4").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("1").assertCountEquals(2)
        DeviceEvidence.capture("results-summary-narrow-font200")
    }

    @Test
    fun normalPhoneSummaryKeepsThreeColumns() {
        showResults(411, 1f)
        val bounds = captions.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (first, second) ->
            assertTrue("Summary columns must remain side by side when words fit", first.right < second.left)
            assertEquals(first.top, second.top, 1f)
        }
        DeviceEvidence.capture("results-summary-normal-phone")
    }

    private fun showResults(width: Int, fontScale: Float) {
        compose.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity, fontScale)) {
                AnchorTheme {
                    Box(Modifier.width(width.dp).fillMaxHeight()) {
                        FinishScreen(SampleLesson.book, Progress(visited = setOf(0, 1, 2), answers = mapOf(0 to Outcome.CORRECT, 1 to Outcome.WRONG, 2 to Outcome.SKIPPED)),
                            SampleLesson.questions, {}, {}, {}, checkComplete = true)
                    }
                }
            }
        }
    }
}
