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
import com.anchorreader.data.Progress
import com.anchorreader.data.SampleLesson
import com.anchorreader.ui.AnchorTheme
import com.anchorreader.ui.OverviewScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverviewActionsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun narrowLargeTextKeepsWholeActionLabelsAndBindings() {
        val actions = mutableListOf<String>()
        showOverview(320, 2f, actions)
        listOf("Full text", "Source").forEach { label ->
            val text = compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Whole action label must fit at the user's text size: $label", 1, layouts.single().lineCount)
            compose.onNode(hasText(label) and hasClickAction()).performScrollTo().assertIsDisplayed().performClick()
        }
        assertEquals(listOf("full-text", "source"), actions)
    }

    @Test
    fun normalPhoneKeepsPairedActionOrder() {
        showOverview(411, 1f, mutableListOf())
        val fullText = compose.onNodeWithText("Full text", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val source = compose.onNodeWithText("Source", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(fullText.right < source.left)
        assertEquals(fullText.top, source.top, 1f)
    }

    private fun showOverview(width: Int, fontScale: Float, actions: MutableList<String>) {
        val book = SampleLesson.book.copy(isSample = false, pdfPath = "/qa-source.pdf",
            title = "A long source title for paired reading actions at the largest supported text size")
        compose.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity, fontScale)) {
                AnchorTheme {
                    Box(Modifier.width(width.dp).fillMaxHeight()) {
                        OverviewScreen(book, Progress(visited = setOf(0)), {}, { actions += "source" },
                            { actions += "full-text" }, {})
                    }
                }
            }
        }
    }
}
