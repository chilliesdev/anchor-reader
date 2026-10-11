package com.anchorreader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchorreader.data.Book
import com.anchorreader.data.Concept
import com.anchorreader.data.Progress
import com.anchorreader.ui.AnchorTheme
import com.anchorreader.ui.LibraryScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryCardsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val units = listOf(Concept("One topic", listOf("A complete sentence.")))
    private val books = listOf(
        Book("sample-card", "Prepared book", "Prepared author", units, "A complete sentence.", isSample = true),
        Book("text-card", "Text book", "Text author", units, "A complete sentence."),
        Book("pdf-card", "PDF book", "PDF author", units, "A complete sentence.", pdfPath = "/missing-source.pdf"),
    )

    @Test
    fun allBookTypesKeepMetadataAndSeparatePlaybackFromDetails() {
        val opened = mutableListOf<String>()
        val details = mutableListOf<String>()
        compose.setContent {
            AnchorTheme { LibraryScreen(books, emptyMap(), { opened += it.id }, { details += it.id }) }
        }
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        books.forEach { book ->
            scrollToBook(book)
            compose.onNodeWithText(book.author, useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("book_cover_${book.id}", useUnmergedTree = true).assertExists()
            compose.onNode(hasText("Ready") and hasAnyAncestor(hasTestTag("book_card_${book.id}")), useUnmergedTree = true).assertExists()
            compose.onNodeWithContentDescription("Details for ${book.title}").performScrollTo()
            val bounds = compose.onNodeWithContentDescription("Details for ${book.title}").fetchSemanticsNode().boundsInRoot
            assertEquals(48 * density, bounds.width, 1f)
            assertEquals(48 * density, bounds.height, 1f)
            compose.onNodeWithContentDescription("Details for ${book.title}").performClick()
            assertEquals(listOf(book.id), details)
            assertEquals(emptyList<String>(), opened)
            compose.onNodeWithText(book.title, useUnmergedTree = true).performScrollTo().performClick()
            assertEquals(listOf(book.id), opened)
            compose.onNodeWithContentDescription("Start ${book.title}").performScrollTo().performClick()
            assertEquals(listOf(book.id, book.id), opened)
            opened.clear()
            details.clear()
        }
    }

    @Test
    fun continueActionAndDetailsNamesMatchAcrossBookTypes() {
        val progress = books.associate { it.id to Progress(visited = setOf(0)) }
        compose.setContent { AnchorTheme { LibraryScreen(books, progress, {}, {}) } }
        books.forEach { book ->
            scrollToBook(book)
            compose.onNodeWithContentDescription("Continue ${book.title}").performScrollTo().assertHasClickAction()
            compose.onNodeWithContentDescription("Details for ${book.title}").assertHasClickAction()
            compose.onNodeWithText("Details").assertDoesNotExist()
        }
    }

    private fun scrollToBook(book: Book) {
        compose.onNodeWithTag("library_list").performScrollToNode(hasContentDescription("Open ${book.title}"))
    }
}
