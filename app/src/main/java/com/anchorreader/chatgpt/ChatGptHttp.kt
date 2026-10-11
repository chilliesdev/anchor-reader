package com.anchorreader.chatgpt

import org.json.JSONObject
import java.io.Closeable
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal interface ChatGptHttp {
    fun open(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null): HttpReply
    fun cancelAll()
}

internal class HttpReply(
    val status: Int,
    val headers: Map<String, List<String>>,
    val input: InputStream,
    private val release: () -> Unit,
) : Closeable {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    override fun close() { try { input.close() } finally { release() } }
}

/** Endpoint injection is internal for tests. Production has no endpoint setting. */
internal class PlatformChatGptHttp(
    private val allowedOrigins: Set<String> = setOf("https://auth.openai.com", "https://api.openai.com"),
    private val testConnection: ((URL) -> HttpURLConnection)? = null,
) : ChatGptHttp {
    private val active = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    override fun open(url: String, method: String, headers: Map<String, String>, body: ByteArray?): HttpReply {
        val uri = URI(url)
        val origin = "${uri.scheme}://${uri.rawAuthority}"
        require(origin in allowedOrigins && uri.rawUserInfo == null && uri.fragment == null) { "Untrusted provider endpoint." }
        require(uri.scheme == "https" || (testConnection != null && uri.host == "127.0.0.1")) { "Provider TLS is required." }
        val connection = testConnection?.invoke(URL(url)) ?: (URL(url).openConnection() as HttpsURLConnection)
        active += connection
        val deadline = timer.schedule({ connection.disconnect() }, 180, TimeUnit.SECONDS)
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.setRequestProperty("Accept", if (url.endsWith("/responses")) "text/event-stream" else "application/json")
            if (body != null) {
                require(body.size <= 256 * 1024)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
            val responseHeaders = connection.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
            return HttpReply(status, responseHeaders, input) {
                deadline.cancel(false)
                active -= connection
                connection.disconnect()
            }
        } catch (error: Exception) {
            deadline.cancel(false)
            active -= connection
            connection.disconnect()
            throw error
        }
    }

    override fun cancelAll() { active.toList().forEach { it.disconnect() } }

    companion object {
        private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "chatgpt-request-deadline").apply { isDaemon = true }
        }
    }
}

internal fun formBody(fields: Map<String, String>): ByteArray = fields.entries.joinToString("&") { (key, value) ->
    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
}.toByteArray(Charsets.UTF_8)

internal fun readBounded(input: InputStream, limit: Int = 1024 * 1024): String {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        require(output.size() + count <= limit) { "Provider response is too large." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}

internal fun providerHttpError(reply: HttpReply): ProviderException {
    val json = runCatching { JSONObject(readBounded(reply.input, 64 * 1024)) }.getOrNull()
    val code = json?.optJSONObject("error")?.optString("code") ?: json?.nullableString("code")
    return providerErrorCode(code, reply.status)
}

internal fun providerErrorCode(code: String?, status: Int): ProviderException {
    return when {
        code == "subscription_sharing_usage_limit_exceeded" || code == "insufficient_quota" ->
            ProviderException(ProviderFailureKind.LIMIT, "ChatGPT usage limit reached. Open Manage usage.")
        code == "subscription_sharing_user_not_eligible" || status == 403 ->
            ProviderException(ProviderFailureKind.NO_PLAN, "ChatGPT plan use is not available for this account.")
        code == "subscription_sharing_invalid_user" || status == 401 -> ProviderException(ProviderFailureKind.AUTH, "Connect ChatGPT again to continue.")
        code == "subscription_sharing_usage_unavailable" || status == 429 || status >= 500 -> ProviderException(ProviderFailureKind.NETWORK, "ChatGPT is unavailable. Try again later.")
        else -> ProviderException(ProviderFailureKind.INVALID_RESPONSE, "ChatGPT could not make questions with this request.")
    }
}
