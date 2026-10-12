package com.anchorreader

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchorreader.data.Book
import com.anchorreader.data.BookRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverviewLargeBookDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var repository: BookRepository
    private lateinit var book: Book
    private lateinit var model: ReaderViewModel
    private var createdBook = false

    @Before
    fun launch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = BookRepository(context)
        val fixtureId = InstrumentationRegistry.getArguments().getString("outlineFixtureId")
        createdBook = fixtureId == null
        book = if (fixtureId != null) repository.loadBooks().single { it.id == fixtureId } else {
            val source = (1..3000).joinToString("\n\n") { "Section $it\n\nRead one complete source sentence." }
            repository.saveText("QA large lazy outline", "QA", source)
        }
        assertEquals(3000, book.units.size)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            model = ViewModelProvider(it, ViewModelProvider.AndroidViewModelFactory.getInstance(it.application))[ReaderViewModel::class.java]
        }
        compose.waitUntil(10_000) { !model.state.value.loading }
    }

    @After
    fun close() {
        if (::scenario.isInitialized) scenario.close()
        if (createdBook && ::book.isInitialized) repository.delete(book)
    }

    @Test
    fun acceptedLargeBookComposesOnlyVisibleRowsAndLastConceptOpens() {
        compose.onNodeWithContentDescription("Details for ${book.title}").performScrollTo()
        val openedAt = SystemClock.elapsedRealtime()
        compose.onNodeWithContentDescription("Details for ${book.title}").performClick()
        compose.onNodeWithTag("overview_concept_0").assertExists()
        Log.i("AnchorReaderQA", "Large outline Details rendered in ${SystemClock.elapsedRealtime() - openedAt}ms")
        assertBoundedRows()
        compose.onNodeWithText("0 of 3000 visited").assertExists()

        val navigatedAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag("overview_list").performScrollToIndex(book.units.lastIndex + 2)
        compose.onNode(hasText(book.units.last().title) and hasClickAction()).performClick()
        compose.waitUntil(10_000) { model.state.value.screen == Screen.PLAYER }
        compose.onNodeWithTag("current_sentence").assertTextEquals(book.units.last().sentences.single())
        assertEquals(book.units.lastIndex, model.state.value.currentProgress.unitIndex)
        assertEquals(setOf(book.units.lastIndex), model.state.value.currentProgress.visited)
        compose.waitUntil(10_000) { repository.loadProgress(book.id).unitIndex == book.units.lastIndex }
        Log.i("AnchorReaderQA", "Large outline last-row selection and saved Player progress in ${SystemClock.elapsedRealtime() - navigatedAt}ms")
    }

    private fun assertBoundedRows() {
        val rows = compose.onAllNodes(SemanticsMatcher("outline concept") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("overview_concept_") == true
        }, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Outline must compose visible rows, not all ${book.units.size}: ${rows.size}", rows.size in 1..30)
    }
}
