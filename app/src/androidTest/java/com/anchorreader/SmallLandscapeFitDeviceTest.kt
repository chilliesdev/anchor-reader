package com.anchorreader

import android.os.ParcelFileDescriptor
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchorreader.data.Book
import com.anchorreader.data.Concept
import com.anchorreader.data.Progress
import com.anchorreader.ui.AnchorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Exact native viewport/font configuration; restored even if a layout assertion fails. */
@RunWith(AndroidJUnit4::class)
class SmallLandscapeFitDeviceTest {
    private val compose = createComposeRule()
    private val name = TestName()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(name).around(Viewport { name.methodName.startsWith("regularPortrait") }).around(compose)

    @Test fun eachLandscapePartFitsWithReadableTextAndCompleteTouchControls() {
        val text = "Read all words, including café, e\u0301 and 😀; ".repeat(15) + "unbroken".repeat(25) + "!  "
        val book = Book("small-landscape", "Landscape source", "QA", listOf(
            Concept("First concept", listOf(text)), Concept("Next concept", listOf("The next concept."))), text)
        var voiceReady by mutableStateOf(true)
        val events = mutableListOf<String>()
        compose.setContent {
            AnchorTheme {
                PlayerScreen(book, Progress(), VoiceState(ready = voiceReady, initializing = false, status = UnavailableVoiceNotice), true, false,
                    onBack = {}, onTranscript = {}, onPlay = { events += "play" }, onReplay = {}, onSpeed = {},
                    onPrevious = {}, onNext = {}, onConceptSettled = { events += "concept" }, onCheck = null,
                    onStill = {}, onSource = {}, onVoiceSettings = {}, onPreviousSentence = {}, onNextSentence = {})
            }
        }
        assertTrue(shell("wm size").contains("1470x840"))
        assertEquals(2f, shell("settings get system font_scale").trim().toFloat(), 0f)
        val count = count()
        assertTrue("Long source must use readable parts", count > 1)
        assertTrue("Reject a pathological single-character pagination", count < text.codePointCount(0, text.length) / 8)
        compose.onNodeWithTag("previous_sentence_part").assertIsNotEnabled()
        val parts = mutableListOf<String>()
        repeat(count) { part ->
            checkFit()
            compose.onNodeWithTag("sentence_count").assertTextEquals("1 / 1")
            compose.onNodeWithTag("sentence_part_count").assertTextEquals("Part ${part + 1} of $count")
            parts += compose.onNodeWithTag("current_sentence").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
            if (part == 0 || part == count / 2 || part == count - 1) {
                compose.waitForIdle()
                DeviceEvidence.capture("issue28-small-landscape200-part-${if (part == 0) "first" else if (part == count - 1) "last" else "middle"}")
            }
            if (part < count - 1) compose.onNodeWithTag("next_sentence_part").performClick()
        }
        compose.onNodeWithTag("next_sentence_part").assertIsNotEnabled()
        assertEquals(text, parts.joinToString(""))
        repeat(count - 1) { compose.onNodeWithTag("previous_sentence_part").performClick(); checkFit() }
        compose.onNodeWithTag("current_sentence").assertTextEquals(parts.first())
        compose.onNodeWithTag("previous_sentence_part").assertIsNotEnabled()
        assertTrue(events.isEmpty())
        compose.onNodeWithContentDescription("Play narration").assertIsDisplayed()
        // Initial/unavailable voice notices must not remove the reserved sentence space.
        compose.runOnIdle { voiceReady = false }
        compose.waitForIdle()
        DeviceEvidence.capture("issue28-small-landscape200-unavailable-notice")
        checkFit()
        compose.onNodeWithText("Voice settings").performScrollTo().assertIsDisplayed()
    }

    @Test fun regularPortraitUnavailableVoiceKeepsEveryPartAndNativeActions() {
        val text = "Read all words, including café, e\u0301 and 😀; ".repeat(15) + "unbroken".repeat(25) + "!  "
        val book = Book("regular-portrait", "Unavailable voice", "QA", listOf(
            Concept("First concept", listOf(text)), Concept("Next concept", listOf("The next concept."))), text)
        var unit by mutableIntStateOf(0)
        val events = mutableListOf<String>()
        compose.setContent {
            AnchorTheme {
                PlayerScreen(book, Progress(unitIndex = unit), VoiceState(ready = false, initializing = false, status = UnavailableVoiceNotice), true, false,
                    onBack = {}, onTranscript = {}, onPlay = { events += "play" }, onReplay = {}, onSpeed = {},
                    onPrevious = { unit = 0 }, onNext = { unit = 1 }, onConceptSettled = { events += "concept"; unit = it },
                    onCheck = null, onStill = {}, onSource = { events += "source" }, onVoiceSettings = { events += "settings" },
                    onPreviousSentence = {}, onNextSentence = {})
            }
        }
        assertTrue(shell("wm size").contains("840x1470"))
        assertEquals(2f, shell("settings get system font_scale").trim().toFloat(), 0f)
        compose.onNode(hasText(UnavailableVoiceNotice) and hasAnyAncestor(hasTestTag("concept_footer_0"))).assertExists()
        val count = count()
        assertTrue(count > 1)
        compose.onNodeWithTag("previous_sentence_part").assertIsNotEnabled()
        val parts = mutableListOf<String>()
        repeat(count) { part ->
            checkFit()
            // Sentence count remains available in its bounded native footer.
            compose.onNodeWithTag("sentence_count").assertTextEquals("1 / 1")
            parts += compose.onNodeWithTag("current_sentence").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
            if (part == 0 || part == count / 2 || part == count - 1) DeviceEvidence.capture(
                "issue28-portrait200-unavailable-part-${if (part == 0) "first" else if (part == count - 1) "last" else "middle"}")
            if (part < count - 1) compose.onNodeWithTag("next_sentence_part").performClick()
        }
        compose.onNodeWithTag("next_sentence_part").assertIsNotEnabled()
        assertEquals(text, parts.joinToString(""))
        assertEquals(0, unit)
        assertTrue(events.isEmpty())
        val settings = compose.onNode(hasText("Voice settings") and hasClickAction() and
            hasAnyAncestor(hasTestTag("concept_footer_0"))).performScrollTo().assertIsDisplayed()
        assertTarget(settings, "Voice settings")
        settings.performClick()
        compose.onNodeWithContentDescription("Play narration").performScrollTo().assertIsNotEnabled()
        val source = compose.onNodeWithContentDescription("Open source").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        DeviceEvidence.capture("issue28-portrait200-unavailable-source-target")
        assertTarget(source, "Source")
        source.performClick()
        DeviceEvidence.capture("issue28-portrait200-unavailable-actions")
        assertEquals(listOf("settings", "source"), events)
        checkFit()
        // No sentence scroll consumes this drag, even with a scrollable notice footer.
        val sentence = compose.onNodeWithTag("current_sentence")
        val sentenceBounds = sentence.getUnclippedBoundsInRoot()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        // This part is one line high; start on its text and flick through the full page.
        sentence.performTouchInput {
            swipe(start = center, end = Offset(center.x, (48f - sentenceBounds.top.value) * density), durationMillis = 150)
        }
        compose.waitUntil(5_000) { unit == 1 }
        compose.onNodeWithText("CONCEPT 2 OF 2").assertExists()
        assertEquals(1, unit)
        assertEquals(listOf("settings", "source", "concept"), events)
        DeviceEvidence.capture("issue28-portrait200-unavailable-text-swipe-settled")
    }

    private fun assertTarget(node: SemanticsNodeInteraction, label: String) {
        val bounds = node.getUnclippedBoundsInRoot()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val minimum = (48f * density).roundToInt()
        assertTrue("$label width >= 48dp: $bounds", ((bounds.right - bounds.left).value * density).roundToInt() >= minimum)
        assertTrue("$label height >= 48dp: $bounds", ((bounds.bottom - bounds.top).value * density).roundToInt() >= minimum)
    }

    private fun checkFit() {
        val sentence = compose.onNodeWithTag("current_sentence")
        val layouts = mutableListOf<TextLayoutResult>()
        sentence.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("Every part must fit", layouts.single().hasVisualOverflow)
        assertTrue("Preserve the readable base font floor", layouts.single().layoutInput.style.fontSize.value >= 16f)
        val bounds = sentence.getUnclippedBoundsInRoot()
        val controls = compose.onNodeWithTag("sentence_parts").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag("concept_title_0").getUnclippedBoundsInRoot()
        assertTrue("Sentence and controls must not overlap", bounds.bottom <= controls.top)
        assertTrue("Controls and title must not overlap: controls=$controls title=$title sentence=$bounds", controls.bottom <= title.top)
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        listOf("previous_sentence_part" to "Back", "next_sentence_part" to "Next").forEach { (tag, label) ->
            val button = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$label width >= 48dp", (button.right - button.left).value >= 48f)
            assertTrue("$label height >= 48dp", (button.bottom - button.top).value >= 48f)
            val labelLayouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(labelLayouts) }
            val layout = labelLayouts.single()
            assertEquals("$label stays a whole word", 1, layout.lineCount)
            assertFalse("$label height fits", layout.didOverflowHeight)
            assertTrue("$label glyphs fit full button width", layout.getLineRight(0) - layout.getLineLeft(0) <=
                ((button.right - button.left).value - 16f) * density + 1f)
        }
    }

    private fun count() = compose.onNodeWithTag("sentence_part_count").fetchSemanticsNode()
        .config[SemanticsProperties.Text].single().text.substringAfterLast(" ").toInt()

    private class Viewport(private val portrait: () -> Boolean) : ExternalResource() {
        private var size: String? = null
        private var density: String? = null
        private var font = "1.0"
        override fun before() {
            size = Regex("Override size: ([0-9]+x[0-9]+)").find(shell("wm size"))?.groupValues?.get(1)
            density = Regex("Override density: ([0-9]+)").find(shell("wm density"))?.groupValues?.get(1)
            font = shell("settings get system font_scale").trim().toFloatOrNull()?.toString() ?: "1.0"
            try {
                shell("wm size ${if (portrait()) "840x1470" else "1470x840"}")
                shell("wm density 420")
                shell("settings put system font_scale 2.0")
            } catch (error: Exception) { after(); throw error }
        }
        override fun after() {
            shell("wm size ${size ?: "reset"}")
            shell("wm density ${density ?: "reset"}")
            shell("settings put system font_scale $font")
        }
    }

    companion object {
        private const val UnavailableVoiceNotice = "Install an offline English voice in device voice settings. You can still read all text."
        private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    }
}
