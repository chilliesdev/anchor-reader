package com.anchorreader.data.web

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebContentExtractorTest {

    @After
    fun tearDown() {
        WebHttp.resetTransport()
    }

    @Test
    fun `findUrl extracts clean URL from various share text formats`() {
        assertEquals(
            "https://twitter.com/jack/status/20",
            WebContentExtractor.findUrl("https://twitter.com/jack/status/20")
        )
        assertEquals(
            "https://twitter.com/jack/status/20",
            WebContentExtractor.findUrl("Check this out: https://twitter.com/jack/status/20!")
        )
        assertEquals(
            "https://www.reddit.com/r/androiddev/comments/123/test/",
            WebContentExtractor.findUrl("Look at this discussion (https://www.reddit.com/r/androiddev/comments/123/test/).")
        )
        assertEquals(
            "https://example.com/article",
            WebContentExtractor.findUrl("Reading https://example.com/article, highly recommended.")
        )
        assertNull(WebContentExtractor.findUrl("No URL in this string."))
    }

    @Test
    fun `extract routes Twitter URL to Twitter extractor`() {
        WebHttp.transport = object : WebHttpTransport {
            override fun get(url: String, headers: Map<String, String>): WebHttpResponse {
                val json = """
                    {
                        "code": 200,
                        "tweet": {
                            "text": "Routed to Twitter extractor successfully.",
                            "author": { "name": "Author", "screen_name": "author" }
                        }
                    }
                """.trimIndent()
                return WebHttpResponse(200, json, finalUrl = url)
            }
        }

        val result = WebContentExtractor.extract("Shared from X: https://x.com/author/status/555555")
        assertEquals("Author (@author)", result.author)
        assertTrue(result.fullText.contains("Routed to Twitter extractor successfully."))
    }

    @Test
    fun `extract routes generic URL to GenericArticleExtractor`() {
        WebHttp.transport = object : WebHttpTransport {
            override fun get(url: String, headers: Map<String, String>): WebHttpResponse {
                val html = """
                    <html>
                    <head><title>Clean Architecture Essay</title></head>
                    <body><article><p>First sentence. Second sentence. Third sentence.</p></article></body>
                    </html>
                """.trimIndent()
                return WebHttpResponse(200, html, finalUrl = url)
            }
        }

        val result = WebContentExtractor.extract("https://martinfowler.com/articles/architecture.html")
        assertEquals("Clean Architecture Essay", result.title)
        assertTrue(result.fullText.contains("First sentence. Second sentence. Third sentence."))
    }

    @Test
    fun `extract throws on text without URL`() {
        try {
            WebContentExtractor.extract("Just plain text without link")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("No valid http or https URL"))
        }
    }
}
