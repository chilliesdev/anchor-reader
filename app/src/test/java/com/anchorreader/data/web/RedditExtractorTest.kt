package com.anchorreader.data.web

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RedditExtractorTest {

    @After
    fun tearDown() {
        WebHttp.resetTransport()
    }

    @Test
    fun `extracts Reddit post selftext and top comments from public json endpoint`() {
        WebHttp.transport = object : WebHttpTransport {
            override fun get(url: String, headers: Map<String, String>): WebHttpResponse {
                val json = """
                    [
                        {
                            "kind": "Listing",
                            "data": {
                                "children": [
                                    {
                                        "kind": "t3",
                                        "data": {
                                            "title": "Why Kotlin Multiplatform is gaining adoption",
                                            "author": "kotlin_fan",
                                            "subreddit": "androiddev",
                                            "selftext": "Sharing business logic across Android and iOS saves engineering time. The UI remains 100% native.",
                                            "score": 350
                                        }
                                    }
                                ]
                            }
                        },
                        {
                            "kind": "Listing",
                            "data": {
                                "children": [
                                    {
                                        "kind": "t1",
                                        "data": {
                                            "author": "AutoModerator",
                                            "body": "Please read the subreddit rules.",
                                            "score": 1
                                        }
                                    },
                                    {
                                        "kind": "t1",
                                        "data": {
                                            "author": "[deleted]",
                                            "body": "This was removed.",
                                            "score": 5
                                        }
                                    },
                                    {
                                        "kind": "t1",
                                        "data": {
                                            "author": "mobile_dev",
                                            "body": "We migrated our data layer to KMP six months ago and it has been rock solid.",
                                            "score": 85
                                        }
                                    },
                                    {
                                        "kind": "t1",
                                        "data": {
                                            "author": "ios_guru",
                                            "body": "Swift export is getting much smoother with the new direct Swift export plugin.",
                                            "score": 42
                                        }
                                    }
                                ]
                            }
                        }
                    ]
                """.trimIndent()
                return WebHttpResponse(200, json, finalUrl = url)
            }
        }

        val article = RedditExtractor.extract("https://www.reddit.com/r/androiddev/comments/123456/why_kotlin_multiplatform_is_gaining_adoption/")

        assertEquals("Why Kotlin Multiplatform is gaining adoption", article.title)
        assertEquals("u/kotlin_fan (r/androiddev)", article.author)

        // 3 concepts: 1 post + 2 valid comments (AutoModerator and [deleted] are filtered out)
        assertEquals(3, article.units.size)

        assertEquals("Post · r/androiddev", article.units[0].title)
        assertTrue(article.units[0].text.contains("Sharing business logic across Android and iOS"))

        assertEquals("Comment · u/mobile_dev (↑85)", article.units[1].title)
        assertTrue(article.units[1].text.contains("We migrated our data layer to KMP"))

        assertEquals("Comment · u/ios_guru (↑42)", article.units[2].title)
        assertTrue(article.units[2].text.contains("Swift export is getting much smoother"))

        assertFalse(article.fullText.contains("Please read the subreddit rules."))
        assertFalse(article.fullText.contains("This was removed."))
    }

    @Test
    fun `extracts link-only Reddit post correctly`() {
        WebHttp.transport = object : WebHttpTransport {
            override fun get(url: String, headers: Map<String, String>): WebHttpResponse {
                val json = """
                    [
                        {
                            "kind": "Listing",
                            "data": {
                                "children": [
                                    {
                                        "kind": "t3",
                                        "data": {
                                            "title": "Announcing Android 15 Beta",
                                            "author": "news_bot",
                                            "subreddit": "android",
                                            "selftext": "",
                                            "url": "https://android-developers.googleblog.com/2026/android-15-beta.html"
                                        }
                                    }
                                ]
                            }
                        },
                        {
                            "kind": "Listing",
                            "data": {
                                "children": []
                            }
                        }
                    ]
                """.trimIndent()
                return WebHttpResponse(200, json, finalUrl = url)
            }
        }

        val article = RedditExtractor.extract("https://redd.it/abcdef")

        assertEquals("Announcing Android 15 Beta", article.title)
        assertEquals(1, article.units.size)
        assertTrue(article.units[0].text.contains("Link: https://android-developers.googleblog.com/2026/android-15-beta.html"))
    }

    @Test
    fun `rejects invalid Reddit URL`() {
        try {
            RedditExtractor.extract("https://www.reddit.com/user/someone")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Not a valid Reddit post link"))
        }
    }
}
