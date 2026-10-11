package com.anchorreader.chatgpt

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Injected Java connection only; no Android wire headers or external request. */
class ProviderHeaderExtractionTest {
    @Test fun platformExtractionPreservesOrderListsAndFirstLookupWithoutReadingInput() {
        val cases: List<Pair<Map<String?, List<String>>, String?>> = listOf(
            linkedMapOf<String?, List<String>>(null to listOf("HTTP/1.1 200"), "Content-Type" to listOf("text/event-stream"), "X-Fixture" to listOf("FAKE_SECRET_HEADER")) to "text/event-stream",
            linkedMapOf<String?, List<String>>(null to listOf("HTTP/1.1 200"), "cOnTeNt-TyPe" to listOf("TEXT/EVENT-STREAM")) to "TEXT/EVENT-STREAM",
            linkedMapOf<String?, List<String>>("Content-Type" to emptyList<String>(), "CONTENT-TYPE" to listOf("text/event-stream")) to null,
            linkedMapOf<String?, List<String>>("Content-Type" to listOf("", "text/event-stream")) to "",
            linkedMapOf<String?, List<String>>("content-type" to listOf("application/json", "text/event-stream")) to "application/json",
            linkedMapOf<String?, List<String>>("Content-Type" to listOf("text/html"), "content-type" to listOf("text/event-stream")) to "text/html",
            linkedMapOf<String?, List<String>>(null to listOf("HTTP/1.1 200"), "X-Fixture" to listOf("FAKE_SECRET_HEADER")) to null,
        )
        for ((fields, selected) in cases) {
            val connection = HeaderConnection(fields)
            val transport = connection.transport()
            val reply = transport.open(connection.url.toString())
            assertEquals(listOf("status", "input", "headers"), connection.calls)
            val expected = fields.filterKeys { it != null }
            assertEquals(expected.keys.toList(), reply.headers.keys.toList())
            assertEquals(expected, reply.headers)
            expected.forEach { (key, values) -> assertSame(values, reply.headers[key]) }
            assertEquals(selected, reply.header("CONTENT-TYPE"))
            assertEquals(0, connection.body.reads)
            assertEquals(0, connection.disconnects)
            assertFalse(connection.body.closed)
            reply.close()
            assertTrue(connection.body.closed)
            assertEquals(1, connection.disconnects)
            transport.cancelAll()
            assertEquals(1, connection.disconnects)
        }
    }

    @Test fun extractionFailuresDisconnectAndRemoveTheActiveConnectionWithoutReadingTheBody() {
        for (stage in listOf("status", "input", "headers")) {
            val connection = HeaderConnection(emptyMap(), failureStage = stage)
            val transport = connection.transport()
            val error = runCatching { transport.open(connection.url.toString()) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals(0, connection.body.reads)
            assertEquals(1, connection.disconnects)
            transport.cancelAll()
            assertEquals(1, connection.disconnects)
        }
    }

    @Test fun errorStatusUsesTheErrorStreamAndCloseReleasesEvenWhenInputCloseThrows() {
        val connection = HeaderConnection(mapOf("Content-Type" to listOf("application/json")), status = 503)
        val transport = connection.transport()
        val reply = transport.open(connection.url.toString())
        assertEquals(listOf("status", "error", "headers"), connection.calls)
        assertSame(connection.body, reply.input)
        assertEquals(0, connection.body.reads)
        connection.body.closeFailure = true
        assertTrue(runCatching { reply.close() }.exceptionOrNull() is IOException)
        assertTrue(connection.body.closed)
        assertEquals(1, connection.disconnects)
        transport.cancelAll()
        assertEquals(1, connection.disconnects)
    }
}

private class HeaderConnection(
    private val fields: Map<String?, List<String>>,
    private val status: Int = 200,
    private val failureStage: String? = null,
) : HttpURLConnection(URL("https://FAKE_SECRET_URL.test/fixture")) {
    val calls = mutableListOf<String>()
    val body = HeaderInput()
    var disconnects = 0
    fun transport() = PlatformChatGptHttp(setOf("https://FAKE_SECRET_URL.test")) { this }
    private fun called(stage: String) {
        calls += stage
        if (failureStage == stage) throw IOException("FAKE_SECRET_EXCEPTION")
    }
    override fun getResponseCode(): Int { called("status"); return status }
    override fun getInputStream(): InputStream { called("input"); return body }
    override fun getErrorStream(): InputStream { called("error"); return body }
    override fun getHeaderFields(): Map<String?, List<String>> { called("headers"); return fields }
    override fun disconnect() { disconnects++ }
    override fun connect() = Unit
    override fun usingProxy() = false
}

private class HeaderInput : ByteArrayInputStream("FAKE_SECRET_BODY".toByteArray()) {
    var reads = 0
    var closed = false
    var closeFailure = false
    override fun read(): Int { reads++; return super.read() }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int { reads++; return super.read(bytes, offset, length) }
    override fun close() {
        closed = true
        if (closeFailure) throw IOException("FAKE_SECRET_CLOSE")
        super.close()
    }
}
