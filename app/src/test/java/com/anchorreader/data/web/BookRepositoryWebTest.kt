package com.anchorreader.data.web

import com.anchorreader.data.BookRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookRepositoryWebTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `saveWebArticle persists book with sourceUrl and reloads accurately`() {
        val booksDir = tempFolder.newFolder("books")
        val progressDir = tempFolder.newFolder("progress")
        val repository = BookRepository(booksDir, progressDir)

        val article = ExtractedArticle(
            title = "Substack Newsletter Issue #1",
            author = "Tech Writer",
            units = listOf(
                ConceptDraft("Part 1", "This is the first concept of the article. It has enough letters to be valid."),
                ConceptDraft("Part 2", "This is the second concept of the article. It provides more depth on the subject.")
            ),
            sourceUrl = "https://techwriter.substack.com/p/issue-1",
            fullText = "This is the first concept of the article. It has enough letters to be valid.\n\nThis is the second concept of the article. It provides more depth on the subject."
        )

        val saved = repository.saveWebArticle(article)

        assertEquals("Substack Newsletter Issue #1", saved.title)
        assertEquals("Tech Writer", saved.author)
        assertEquals("https://techwriter.substack.com/p/issue-1", saved.sourceUrl)
        assertEquals(2, saved.units.size)
        assertEquals("Part 1", saved.units[0].title)
        assertEquals("Part 2", saved.units[1].title)

        // Reload from disk
        val loadedBooks = repository.loadBooks()
        val reloaded = loadedBooks.find { it.id == saved.id }
        assertNotNull(reloaded)
        assertEquals("https://techwriter.substack.com/p/issue-1", reloaded!!.sourceUrl)
        assertEquals(2, reloaded.units.size)
    }

    @Test
    fun `backward compatibility loads legacy books without sourceUrl as null`() {
        val booksDir = tempFolder.newFolder("books_legacy")
        val progressDir = tempFolder.newFolder("progress_legacy")

        // Write a legacy JSON metadata file without sourceUrl
        val legacyJson = """
            {
                "version": 3,
                "id": "legacy-book-123",
                "title": "Legacy Book Without Source URL",
                "author": "Classic Author",
                "units": [
                    {
                        "title": "Chapter 1",
                        "sentences": ["First legacy sentence.", "Second legacy sentence."],
                        "sourcePage": null,
                        "sourceStartOffset": 0,
                        "sourceSpans": []
                    }
                ],
                "sourceText": "First legacy sentence. Second legacy sentence.",
                "pdfFile": null,
                "createdAt": 1700000000000
            }
        """.trimIndent()

        File(booksDir, "legacy-book-123.json").writeText(legacyJson, Charsets.UTF_8)

        val repository = BookRepository(booksDir, progressDir)
        val loaded = repository.loadBooks()
        val legacyBook = loaded.find { it.id == "legacy-book-123" }

        assertNotNull(legacyBook)
        assertEquals("Legacy Book Without Source URL", legacyBook!!.title)
        assertNull(legacyBook.sourceUrl)
        assertEquals(1, legacyBook.units.size)
    }
}
