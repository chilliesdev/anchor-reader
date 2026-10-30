package com.anchorreader.data.web

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class WebHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val finalUrl: String
)

interface WebHttpTransport {
    fun get(url: String, headers: Map<String, String> = emptyMap()): WebHttpResponse
}

object DefaultWebHttpTransport : WebHttpTransport {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_BYTES = 5 * 1024 * 1024 // 5 MB
    private const val MAX_REDIRECTS = 5
    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 AnchorReader/1.0"

    override fun get(url: String, headers: Map<String, String>): WebHttpResponse {
        var currentUrl = url
        var redirects = 0
        while (redirects < MAX_REDIRECTS) {
            val parsed = URL(currentUrl)
            require(parsed.protocol == "http" || parsed.protocol == "https") {
                "Unsupported protocol: ${parsed.protocol}"
            }
            val connection = parsed.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            headers.forEach { (k, v) ->
                if (!k.equals("User-Agent", ignoreCase = true)) {
                    connection.setRequestProperty(k, v)
                }
            }
            try {
                connection.connect()
                val code = connection.responseCode
                if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                    if (location != null) {
                        currentUrl = URL(parsed, location).toString()
                        redirects++
                        continue
                    }
                }
                val stream: InputStream = if (code in 200..299) connection.inputStream else (connection.errorStream ?: connection.inputStream)
                val bytes = readStreamLimited(stream, MAX_BYTES)
                val charset = connection.contentEncoding ?: "UTF-8"
                val body = try {
                    String(bytes, java.nio.charset.Charset.forName(charset))
                } catch (_: Exception) {
                    String(bytes, Charsets.UTF_8)
                }
                val headers = connection.headerFields.entries.filter { it.key != null }.associate { it.key to it.value }
                return WebHttpResponse(code, body, headers, currentUrl)
            } finally {
                connection.disconnect()
            }
        }
        throw java.io.IOException("Too many redirects")
    }

    private fun readStreamLimited(stream: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(8192)
        val baos = ByteArrayOutputStream()
        var total = 0
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) {
                    throw java.io.IOException("Response exceeded maximum allowed size of $maxBytes bytes")
                }
                baos.write(buffer, 0, read)
            }
        }
        return baos.toByteArray()
    }
}

object WebHttp {
    @Volatile
    var transport: WebHttpTransport = DefaultWebHttpTransport

    fun get(url: String, headers: Map<String, String> = emptyMap()): WebHttpResponse {
        return transport.get(url, headers)
    }

    fun resetTransport() {
        transport = DefaultWebHttpTransport
    }
}
