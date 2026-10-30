package com.anchorreader.data.web

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class WebHttpTest {

    @After
    fun tearDown() {
        WebHttp.resetTransport()
    }

    @Test
    fun `custom transport receives request and returns response`() {
        var requestedUrl = ""
        var requestHeaders: Map<String, String> = emptyMap()

        WebHttp.transport = object : WebHttpTransport {
            override fun get(url: String, headers: Map<String, String>): WebHttpResponse {
                requestedUrl = url
                requestHeaders = headers
                return WebHttpResponse(200, "{\"status\":\"ok\"}", mapOf("Content-Type" to listOf("application/json")), url)
            }
        }

        val response = WebHttp.get("https://example.com/test", mapOf("Custom-Header" to "value"))
        assertEquals(200, response.statusCode)
        assertEquals("{\"status\":\"ok\"}", response.body)
        assertEquals("https://example.com/test", requestedUrl)
        assertEquals("value", requestHeaders["Custom-Header"])
    }

    @Test
    fun `default transport throws IllegalArgumentException on unsupported protocol`() {
        try {
            DefaultWebHttpTransport.get("ftp://example.com/resource")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Unsupported protocol"))
        }
    }
}
