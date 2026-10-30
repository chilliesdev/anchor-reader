package com.anchorreader.data.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericArticleExtractorTest {

    @Test
    fun `extracts Substack article with headings and cleans title`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>The Future of Mobile Computing - Substack</title>
                <meta property="og:title" content="The Future of Mobile Computing - Substack">
                <meta name="author" content="Alan Kay">
            </head>
            <body>
                <header><nav><a href="/">Home</a></nav></header>
                <article>
                    <p>Mobile devices are ubiquitous today. They allow computation anywhere at any time.</p>
                    <h2>The Early Days</h2>
                    <p>It started with simple cellular devices. Then personal digital assistants emerged.</p>
                    <h2>The Touchscreen Era</h2>
                    <p>Capacitive touchscreens changed everything. Now multi-touch is standard across all platforms.</p>
                </article>
                <aside class="sidebar">Related posts and ads</aside>
                <footer>Copyright 2026</footer>
            </body>
            </html>
        """.trimIndent()

        val article = GenericArticleExtractor.parseHtml(html, "https://substack.example.com/p/mobile-future")

        assertEquals("The Future of Mobile Computing", article.title)
        assertEquals("Alan Kay", article.author)
        assertEquals("https://substack.example.com/p/mobile-future", article.sourceUrl)

        // 3 concepts: Introduction, The Early Days, The Touchscreen Era
        assertEquals(3, article.units.size)
        assertEquals("Introduction", article.units[0].title)
        assertTrue(article.units[0].text.contains("Mobile devices are ubiquitous today"))

        assertEquals("The Early Days", article.units[1].title)
        assertTrue(article.units[1].text.contains("It started with simple cellular devices"))

        assertEquals("The Touchscreen Era", article.units[2].title)
        assertTrue(article.units[2].text.contains("Capacitive touchscreens changed everything"))

        // Verify clutter is stripped
        assertFalse(article.fullText.contains("Related posts and ads"))
        assertFalse(article.fullText.contains("Copyright 2026"))
        assertFalse(article.fullText.contains("Home"))
    }

    @Test
    fun `chunks article by paragraphs into short form concepts when headings are absent`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>A Quiet Morning Story | Medium</title>
                <meta property="article:author" content="Jane Doe">
            </head>
            <body>
                <article>
                    <p>Sentence one describes the dawn. Sentence two explains the weather. Sentence three mentions the coffee.</p>
                    <p>Sentence four introduces the garden. Sentence five watches the birds. Sentence six concludes the breakfast.</p>
                    <p>Sentence seven prepares for work. Sentence eight reviews the agenda. Sentence nine closes the notebook.</p>
                </article>
            </body>
            </html>
        """.trimIndent()

        val article = GenericArticleExtractor.parseHtml(html, "https://medium.example.com/quiet-morning")

        assertEquals("A Quiet Morning Story", article.title)
        assertEquals("Jane Doe", article.author)
        // With 9 sentences chunked into ~3 sentences each, there should be 3 section concepts
        assertTrue(article.units.size in 2..4)
        assertTrue(article.units[0].title.startsWith("Section 1"))
        assertTrue(article.units[0].text.contains("Sentence one describes the dawn."))
    }
}
