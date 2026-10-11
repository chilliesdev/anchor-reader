package com.anchorreader.chatgpt

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.SocketTimeoutException

internal data class AuthorizationCallback(val code: String, val clientId: String?)

/** Runs independently of an Activity. It accepts only one matching local callback. */
internal class LoopbackCallbackServer(private val expectedState: String, private val timeoutMillis: Long = 300_000) : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1_000 }
    @Volatile private var accepted: Socket? = null
    @Volatile private var closed = false
    val redirectUri: String = "http://127.0.0.1:${server.localPort}$CALLBACK_PATH"

    fun await(): AuthorizationCallback {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (!closed && System.nanoTime() < deadline) {
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            accepted = socket
            try {
                socket.soTimeout = 5_000
                val request = readRequest(socket)
                val pieces = request.first().split(' ')
                if (pieces.size != 3 || pieces[0] != "GET" || pieces[2] !in listOf("HTTP/1.0", "HTTP/1.1")) {
                    send(socket, 400, "Invalid request.")
                    continue
                }
                val target = pieces[1]
                if (target.substringBefore('?') != CALLBACK_PATH) {
                    send(socket, 404, "Not found.")
                    continue
                }
                val host = request.drop(1).filter { it.substringBefore(':').equals("Host", true) }
                require(host.size == 1 && host.single().substringAfter(':').trim() == "127.0.0.1:${server.localPort}")
                val query = parseQuery(target.substringAfter('?', ""))
                if (!sameSecret(query["state"] ?: "", expectedState)) {
                    send(socket, 400, "Sign-in could not be verified. Return to AnchorReader.")
                    throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT sign-in could not be verified. Connect again.")
                }
                if (query.containsKey("error")) {
                    send(socket, 200, RETURN_PAGE)
                    throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT sign-in was not completed.")
                }
                val code = query["code"]?.takeIf { it.isNotEmpty() && it.length <= 8_192 }
                    ?: throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT did not return a sign-in code.")
                val client = query["client_id"]
                require(client == null || client.length in 1..512)
                send(socket, 200, RETURN_PAGE)
                closed = true
                server.close()
                return AuthorizationCallback(code, client)
            } finally {
                socket.close()
                accepted = null
            }
        }
        throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT sign-in timed out. Connect again.")
    }

    override fun close() {
        closed = true
        runCatching { accepted?.close() }
        runCatching { server.close() }
    }

    private fun readRequest(socket: Socket): List<String> {
        val input = socket.getInputStream()
        val bytes = java.io.ByteArrayOutputStream()
        var ending = 0
        while (bytes.size() <= 16 * 1024) {
            val next = input.read()
            require(next != -1 && next in 1..127)
            bytes.write(next)
            ending = when {
                (ending == 0 || ending == 2) && next == 13 -> ending + 1
                (ending == 1 || ending == 3) && next == 10 -> ending + 1
                next == 13 -> 1
                else -> 0
            }
            if (ending == 4) return bytes.toByteArray().toString(Charsets.US_ASCII).split("\r\n").filter { it.isNotEmpty() }
        }
        throw ProviderException(ProviderFailureKind.AUTH, "The browser returned an invalid sign-in request.")
    }

    private fun send(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nContent-Security-Policy: default-src 'none'; base-uri 'none'; frame-ancestors 'none'\r\n" +
            "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII) + bytes)
        socket.getOutputStream().flush()
    }

    companion object {
        private const val RETURN_PAGE = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><title>AnchorReader</title></head><body><h1>Return to AnchorReader</h1><p>The app will finish checking your sign-in.</p><p><a href='anchorreader://oauth-return'>Open AnchorReader</a></p></body></html>"
    }
}

internal fun parseQuery(query: String): Map<String, String> {
    require(query.length <= 16 * 1024)
    val result = mutableMapOf<String, String>()
    if (query.isEmpty()) return result
    for (part in query.split('&')) {
        require(part.contains('='))
        val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
        val value = URLDecoder.decode(part.substringAfter('='), "UTF-8")
        require(key.isNotEmpty() && key !in result)
        result[key] = value
    }
    return result
}
