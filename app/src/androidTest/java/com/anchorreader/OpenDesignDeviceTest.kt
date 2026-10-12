package com.anchorreader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anchorreader.data.*
import com.anchorreader.ui.AnchorTheme
import com.anchorreader.ui.PracticeCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenDesignDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun displayPartsPreserveFullSentenceAndPositionAtNarrowLargeText() {
        val text = "Read all words, including café, e\u0301 and 😀; ".repeat(30) + "unbroken".repeat(25) + "!  "
        val state = PlayerFixture(text)
        showPlayer(state, 320, 560, 2f)
        compose.onNodeWithTag("concept_art_0").assertDoesNotExist()
        compose.onNodeWithTag("previous_sentence_part").assertIsNotEnabled()
        val count = partCount()
        assertTrue(count > 1)
        val parts = mutableListOf<String>()
        repeat(count) { part ->
            compose.onNodeWithTag("sentence_part_count").assertTextEquals("Part ${part + 1} of $count")
            val sentence = compose.onNodeWithTag("current_sentence")
            val layouts = mutableListOf<TextLayoutResult>()
            sentence.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            if (layout.hasVisualOverflow) DeviceEvidence.capture("issue28-failed-sentence-fit")
            assertFalse("Display part must not clip: part=${part + 1}/$count size=${layout.size} " +
                "paragraph_height=${layout.multiParagraph.height} width_overflow=${layout.didOverflowWidth} height_overflow=${layout.didOverflowHeight} " +
                "style=${layout.layoutInput.style} constraints=${layout.layoutInput.constraints} text=${layout.layoutInput.text.text}", layout.hasVisualOverflow)
            val bounds = sentence.getUnclippedBoundsInRoot()
            val controls = compose.onNodeWithTag("sentence_parts").getUnclippedBoundsInRoot()
            val title = compose.onNodeWithTag("concept_title_0").getUnclippedBoundsInRoot()
            assertTrue("Parts must stay above their controls", bounds.bottom <= controls.top)
            assertTrue("Part controls must stay above the title", controls.bottom <= title.top)
            listOf("previous_sentence_part" to "Back", "next_sentence_part" to "Next").forEach { (tag, label) ->
                val button = compose.onNodeWithTag(tag)
                val target = button.getUnclippedBoundsInRoot()
                assertTrue("$label touch width must be at least 48dp", (target.right - target.left).value >= 48f)
                assertTrue("$label touch height must be at least 48dp", (target.bottom - target.top).value >= 48f)
                val labelNode = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                val labelLayouts = mutableListOf<TextLayoutResult>()
                labelNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(labelLayouts) }
                assertEquals("$label must remain a whole single-line word at 200%", 1, labelLayouts.single().lineCount)
                assertFalse("$label must not clip", labelLayouts.single().hasVisualOverflow)
                val countBounds = compose.onNodeWithTag("sentence_part_count").getUnclippedBoundsInRoot()
                assertTrue("Count must reflow above full-width labels", countBounds.bottom <= target.top)
            }
            parts += sentence.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
            compose.onNodeWithTag("sentence_count").assertTextEquals("1 / 2")
            if (part < count - 1) compose.onNodeWithTag("next_sentence_part").performClick()
        }
        compose.onNodeWithTag("next_sentence_part").assertIsNotEnabled()
        assertEquals(text, parts.joinToString(""))
        assertEquals(0, state.sentence)
        assertEquals(0, state.unit)
        assertTrue(state.actions.isEmpty())
        DeviceEvidence.capture("issue28-player-narrow-large-text-final-part")
        repeat(count - 1) { compose.onNodeWithTag("previous_sentence_part").performClick() }
        compose.onNodeWithTag("current_sentence").assertTextEquals(parts.first())
        compose.onNodeWithContentDescription("Next sentence").performClick()
        compose.onNodeWithTag("current_sentence").assertTextEquals("A short second sentence.")
        compose.onNodeWithTag("sentence_parts").assertDoesNotExist()
        compose.onNodeWithContentDescription("Previous sentence").performClick()
        compose.onNodeWithTag("sentence_part_count").assertTextEquals("Part 1 of $count")
        compose.onNodeWithTag("next_sentence_part").performClick()
        compose.onNodeWithContentDescription("Next concept").performClick()
        compose.onNodeWithContentDescription("Previous concept").performClick()
        compose.onNodeWithTag("sentence_part_count").assertTextEquals("Part 1 of $count")
        compose.onNodeWithTag("next_sentence_part").performClick()
        compose.runOnIdle { state.book = state.book.copy(id = "other-book") }
        compose.onNodeWithTag("sentence_part_count").assertTextEquals("Part 1 of $count")
    }

    @Test fun sampleKeepsRealDiagramAndImportedShortTextHasNoArtworkSlot() {
        val state = PlayerFixture("A short complete sentence.")
        state.book = state.book.copy(isSample = true)
        showPlayer(state, 411, 800, 1f)
        compose.onNodeWithTag("concept_art_0").assertIsDisplayed()
        compose.onNodeWithTag("sentence_parts").assertDoesNotExist()
        DeviceEvidence.capture("issue28-player-sample-diagram")
        compose.runOnIdle { state.book = state.book.copy(id = "text-only", isSample = false) }
        compose.onNodeWithTag("concept_art_0").assertDoesNotExist()
        compose.onNodeWithTag("current_sentence").assertTextEquals("A short complete sentence.")
        compose.onNodeWithContentDescription("Play narration").assertIsEnabled()
        compose.onNodeWithContentDescription("Open source").assertIsEnabled()
        DeviceEvidence.capture("issue28-player-text-only")
    }

    @Test fun nativePracticeStatesRetainPartialQuestionsAndRecoveryActions() {
        val book = SampleLesson.book.copy(id = "fixture-pdf", isSample = false, pdfPath = "/fixture.pdf")
        val question = SampleLesson.questions.first().copy(id = "saved", version = "v1")
        var record by mutableStateOf(PracticeRecord(book.documentVersion))
        val actions = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                AnchorTheme {
                    Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        PracticeCard(book, record, { actions += "add" }, { actions += "cancel" },
                            { actions += "practice" }, { actions += "usage" })
                    }
                }
            }
        }
        compose.onNodeWithText("Not ready").assertExists()
        compose.onNodeWithText("No questions yet").assertExists()
        click("Add questions")
        DeviceEvidence.capture("issue28-practice-empty-large-text")
        val task = QuestionTask("task", book.documentVersion, "fixture", QuestionTaskStatus.CONNECTING,
            book.units.associate { it.id to SectionQuestionStatus.PENDING })
        compose.runOnIdle { record = record.copy(task = task) }
        compose.onNodeWithText("Generating").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Making questions").assertExists()
        compose.onNodeWithTag("question_generation_progress").assertExists()
        click("Cancel generation")
        compose.runOnIdle { record = record.copy(questions = listOf(question), task = task.copy(status = QuestionTaskStatus.RUNNING)) }
        click("Start practice")
        click("Manage usage")
        for (status in listOf(QuestionTaskStatus.ERROR, QuestionTaskStatus.CANCELED, QuestionTaskStatus.LIMIT, QuestionTaskStatus.PAUSED)) {
            compose.runOnIdle { record = record.copy(task = task.copy(status = status, message = "Saved source and ready questions remain available.")) }
            compose.onNodeWithText("Ready").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("practice_ready_count").assertTextEquals("1")
            click("Start practice")
            click("Retry unfinished sections")
            if (status == QuestionTaskStatus.LIMIT) click("Manage usage")
        }
        assertTrue(actions.containsAll(listOf("add", "cancel", "practice", "usage")))
        assertEquals(listOf(question), record.questions)
        DeviceEvidence.capture("issue28-practice-partial-paused-large-text")
    }

    private fun click(label: String) { compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick() }

    private fun partCount(): Int = compose.onNodeWithTag("sentence_part_count").fetchSemanticsNode()
        .config[SemanticsProperties.Text].single().text.substringAfterLast(" ").toInt()

    private fun showPlayer(state: PlayerFixture, width: Int, height: Int, fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                AnchorTheme {
                    Box(Modifier.width(width.dp).height(height.dp)) {
                        PlayerScreen(state.book, Progress(unitIndex = state.unit, sentenceIndex = state.sentence),
                            VoiceState(ready = true, initializing = false), true, false,
                            onBack = {}, onTranscript = {}, onPlay = { state.actions += "play" }, onReplay = {}, onSpeed = {},
                            onPrevious = { state.unit = 0; state.sentence = 0 }, onNext = { state.unit = 1; state.sentence = 0 },
                            onConceptSettled = { state.unit = it; state.sentence = 0 }, onCheck = null, onStill = {}, onSource = {},
                            onVoiceSettings = {}, onPreviousSentence = { state.sentence = 0 }, onNextSentence = { state.sentence = 1 })
                    }
                }
            }
        }
    }

    private class PlayerFixture(text: String) {
        var book by mutableStateOf(Book("fit", "Sentence fit", "QA", listOf(
            Concept("First concept", listOf(text, "A short second sentence.")),
            Concept("Next concept", listOf("The next concept."))), text))
        var unit by mutableIntStateOf(0)
        var sentence by mutableIntStateOf(0)
        val actions = mutableListOf<String>()
    }
}
