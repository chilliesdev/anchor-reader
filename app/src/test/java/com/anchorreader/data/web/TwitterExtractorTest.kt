package com.anchorreader.data.web

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class TwitterExtractorTest {

    @After
    fun tearDown() {
        WebHttp.resetTransport()
    }

    @Test
    fun `extracts single tweet from FXTwitter API response`() {
        WebHttp.transport = object : WebHttpTransport {
            override fun get(url: String, headers: Map<String, String>): WebHttpResponse {
                val json = """
                    {
                        "code": 200,
                        "message": "OK",
                        "tweet": {
                            "id": "1234567890",
                            "text": "Hello world from AnchorReader! This is a test tweet for narration.",
                            "author": {
                                "name": "Test User",
                                "screen_name": "testuser"
                            }
                        }
                    }
                """.trimIndent()
                return WebHttpResponse(200, json, finalUrl = url)
            }
        }

        val article = TwitterExtractor.extract("https://x.com/testuser/status/1234567890")

        assertEquals("Test User (@testuser)", article.author)
        assertEquals("https://x.com/testuser/status/1234567890", article.sourceUrl)
        assertEquals(1, article.units.size)
        assertEquals("Tweet · @testuser", article.units[0].title)
        assertEquals("Hello world from AnchorReader! This is a test tweet for narration.", article.units[0].text)
    }

    @Test
    fun `extracts multi-tweet thread from FXTwitter API response`() {
        WebHttp.transport = object : WebHttpTransport {
            override fun get(url: String, headers: Map<String, String>): WebHttpResponse {
                val json = """
                    {
                        "code": 200,
                        "message": "OK",
                        "tweet": {
                            "id": "1001",
                            "text": "First tweet in a long thread about architecture.",
                            "author": {
                                "name": "Architect Alice",
                                "screen_name": "alice_arch"
                            },
                            "thread": [
                                {
                                    "id": "1002",
                                    "text": "Second tweet detailing domain boundary separation.",
                                    "author": {
                                        "name": "Architect Alice",
                                        "screen_name": "alice_arch"
                                    }
                                },
                                {
                                    "id": "1003",
                                    "text": "Third tweet showing the clean dependency rule.",
                                    "author": {
                                        "name": "Architect Alice",
                                        "screen_name": "alice_arch"
                                    }
                                }
                            ]
                        }
                    }
                """.trimIndent()
                return WebHttpResponse(200, json, finalUrl = url)
            }
        }

        val article = TwitterExtractor.extract("https://twitter.com/alice_arch/status/1001")

        assertEquals("Thread by @alice_arch (3 tweets)", article.title)
        assertEquals("Architect Alice (@alice_arch)", article.author)
        assertEquals(3, article.units.size)

        assertEquals("Tweet 1/3 · @alice_arch", article.units[0].title)
        assertEquals("First tweet in a long thread about architecture.", article.units[0].text)

        assertEquals("Tweet 2/3 · @alice_arch", article.units[1].title)
        assertEquals("Second tweet detailing domain boundary separation.", article.units[1].text)

        assertEquals("Tweet 3/3 · @alice_arch", article.units[2].title)
        assertEquals("Third tweet showing the clean dependency rule.", article.units[2].text)
    }

    @Test
    fun `falls back to oEmbed when FXTwitter returns error`() {
        WebHttp.transport = object : WebHttpTransport {
            override fun get(url: String, headers: Map<String, String>): WebHttpResponse {
                if (url.contains("api.fxtwitter.com")) {
                    return WebHttpResponse(500, "{\"code\":500,\"message\":\"Server Error\"}", finalUrl = url)
                }
                if (url.contains("publish.twitter.com/oembed")) {
                    val oEmbedJson = """
                        {
                            "url": "https://twitter.com/dev/status/999",
                            "author_name": "Developer Dave",
                            "html": "<blockquote class=\"twitter-tweet\"><p>Sentence one from oEmbed. Sentence two from oEmbed.</p>&mdash; Developer Dave (@dev)</blockquote>"
                        }
                    """.trimIndent()
                    return WebHttpResponse(200, oEmbedJson, finalUrl = url)
                }
                throw IOException("Unexpected url: $url")
            }
        }

        val article = TwitterExtractor.extract("https://x.com/dev/status/999")

        assertEquals("Developer Dave (@dev)", article.author)
        assertEquals(1, article.units.size)
        assertEquals("Tweet · @dev", article.units[0].title)
        assertEquals("Sentence one from oEmbed. Sentence two from oEmbed.", article.units[0].text)
    }

    @Test
    fun `rejects invalid non-status Twitter URL`() {
        try {
            TwitterExtractor.extract("https://x.com/explore")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Not a valid Twitter or X status link"))
        }
    }
}
