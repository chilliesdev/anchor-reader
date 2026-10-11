package com.anchorreader.chatgpt

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

internal data class AuthorizationCallback(val code: String, val clientId: String?)

/** Runs independently of an Activity. It accepts only one matching local callback. */
internal class LoopbackCallbackServer(
    private val expectedState: String,
    private val timeoutMillis: Long = 300_000,
    private val writeReply: (Socket, ByteArray) -> Unit = { socket, bytes ->
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    },
) : Closeable {
    init { require(timeoutMillis in 1..300_000) }
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1_000 }
    @Volatile private var accepted: Socket? = null
    @Volatile private var closed = false
    val redirectUri: String = "http://127.0.0.1:${server.localPort}$CALLBACK_PATH"

    fun await(): AuthorizationCallback {
        val started = System.nanoTime()
        val duration = timeoutMillis * 1_000_000L
        while (!closed && remainingNanos(started, duration) > 0) {
            val socket = try {
                server.soTimeout = remainingTimeout(started, duration, 1_000)
                server.accept()
            } catch (_: SocketTimeoutException) { continue }
            accepted = socket
            try {
                // Chrome may preconnect, abandon, or reset a socket before sending its callback.
                // Only that connection is discarded. It must not end the authorization attempt.
                val request = try { readRequest(socket, started, duration) } catch (_: IOException) { continue }
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
                if (host.size != 1 || host.single().substringAfter(':').trim() != "127.0.0.1:${server.localPort}") {
                    send(socket, 400, "Sign-in could not be verified. Return to AnchorReader.")
                    throw ProviderException(ProviderFailureKind.AUTH, "The browser returned an invalid sign-in request.")
                }
                val query = try { parseQuery(target.substringAfter('?', "")) } catch (_: IllegalArgumentException) {
                    send(socket, 400, "Sign-in could not be verified. Return to AnchorReader.")
                    throw ProviderException(ProviderFailureKind.AUTH, "The browser returned an invalid sign-in request.")
                }
                if (!sameSecret(query["state"] ?: "", expectedState)) {
                    send(socket, 400, "Sign-in could not be verified. Return to AnchorReader.")
                    throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT sign-in could not be verified. Connect again.")
                }
                if (query.containsKey("error")) {
                    val failure = authorizationFailure(query["error"])
                    send(socket, 200, returnPage("Sign-in did not finish", failure.message!!))
                    throw failure
                }
                val code = query["code"]?.takeIf { it.isNotEmpty() && it.length <= 8_192 }
                    ?: throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT did not return a sign-in code.")
                val client = query["client_id"]
                if (client != null && client.length !in 1..512) {
                    throw ProviderException(ProviderFailureKind.AUTH, "The browser returned an invalid sign-in request.")
                }
                send(socket, 200, returnPage("Return to AnchorReader", "The browser returned your sign-in result. Open AnchorReader to check if the connection was saved."))
                closed = true
                server.close()
                return AuthorizationCallback(code, client)
            } finally {
                runCatching { socket.close() }
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

    private fun readRequest(socket: Socket, started: Long, duration: Long): List<String> {
        val input = socket.getInputStream()
        val connectionStarted = System.nanoTime()
        val bytes = java.io.ByteArrayOutputStream()
        var ending = 0
        while (bytes.size() <= 16 * 1024) {
            socket.soTimeout = minOf(remainingTimeout(started, duration, 5_000),
                remainingTimeout(connectionStarted, 5_000_000_000L, 5_000))
            val next = input.read()
            if (next == -1) throw EOFException()
            if (next !in 1..127) throw ProviderException(ProviderFailureKind.AUTH, "The browser returned an invalid sign-in request.")
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
            "Cache-Control: no-store\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'sha256-$RETURN_STYLE_HASH'; base-uri 'none'; frame-ancestors 'none'\r\n" +
            "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        // The callback is validated independently of whether Chrome receives this page.
        // A browser disconnect cannot lose a valid code or replace a typed rejection.
        try { writeReply(socket, headers.toByteArray(Charsets.US_ASCII) + bytes) } catch (_: IOException) { }
    }

    private fun remainingNanos(started: Long, duration: Long) = duration - (System.nanoTime() - started)

    private fun remainingTimeout(started: Long, duration: Long, maximum: Int): Int {
        val nanos = remainingNanos(started, duration)
        if (nanos <= 0) throw SocketTimeoutException()
        return ((nanos + 999_999) / 1_000_000).coerceIn(1, maximum.toLong()).toInt()
    }

    companion object {
        private val RETURN_STYLE = """
            * { box-sizing: border-box; }
            html { color-scheme: light; }
            body {
                margin: 0;
                min-height: 100vh;
                min-height: 100svh;
                padding: clamp(20px, 8vh, 72px) 16px;
                background: #F3F6F7;
                color: #14262D;
                font: 1rem/1.5625 system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
            }
            main {
                width: 100%;
                max-width: 28rem;
                margin: 0 auto;
                padding: 24px;
                border: 1px solid #D4DFE1;
                border-radius: 20px;
                background: #FFFFFF;
            }
            .brand { display: flex; align-items: center; gap: 12px; font-weight: 600; overflow-wrap: anywhere; }
            .anchor {
                display: flex;
                align-items: center;
                justify-content: center;
                flex: 0 0 48px;
                height: 48px;
                border-radius: 12px;
                background: #D7E9E9;
                color: #0E5962;
            }
            .anchor svg { width: 32px; height: 32px; }
            h1 {
                margin: 24px 0 12px;
                font: 400 1.875rem/1.1334 ui-serif, Georgia, serif;
                overflow-wrap: anywhere;
            }
            p { margin: 0; color: #52666D; }
            .open-app {
                display: flex;
                align-items: center;
                justify-content: center;
                min-height: 48px;
                margin-top: 24px;
                padding: 12px 16px;
                border-radius: 12px;
                background: #0E5962;
                color: #FFFFFF;
                font-size: .875rem;
                line-height: 1.4286;
                font-weight: 600;
                text-align: center;
                text-decoration: none;
            }
            .open-app:hover, .open-app:active { background: #093E47; }
            .open-app:focus-visible { outline: 3px solid #0E5962; outline-offset: 4px; }
            .helper { margin-top: 12px; font-size: .875rem; line-height: 1.5; }
            @media (max-width: 359px) {
                main { padding: 20px; }
                h1 { font-size: 1.75rem; line-height: 1.1786; }
            }
            @media (min-width: 600px) {
                body { padding-top: clamp(48px, 16vh, 160px); }
            }
        """.trimIndent()
        // Hash the exact style bytes sent below. No other inline styles or scripts are allowed.
        private val RETURN_STYLE_HASH = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(RETURN_STYLE.toByteArray(Charsets.UTF_8)),
        )
        private fun returnPage(title: String, message: String) = """
            <!doctype html>
            <html lang="en">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>$title</title>
                <style>$RETURN_STYLE</style>
            </head>
            <body>
                <main aria-labelledby="return-title">
                    <div class="brand">
                        <span class="anchor" aria-hidden="true">
                            <svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round">
                                <circle cx="24" cy="5" r="3"/>
                                <path d="M24 8V30M11 23H6M37 23H42M16 14C16 3 32 3 32 14M11 23V28C11 45 37 45 37 28V23"/>
                            </svg>
                        </span>
                        <span>AnchorReader</span>
                    </div>
                    <h1 id="return-title">$title</h1>
                    <p>$message</p>
                    <a class="open-app" href="anchorreader://oauth-return">Open AnchorReader</a>
                    <p class="helper">If the app does not open, switch back to AnchorReader.</p>
                </main>
            </body>
            </html>
        """.trimIndent()
    }
}

/** Fixed OAuth guidance only. Provider text and descriptions never enter the UI or logs. */
internal fun authorizationFailure(error: String?): ProviderException = when (error) {
    "access_denied" -> ProviderException(ProviderFailureKind.AUTH, "ChatGPT sign-in was canceled or declined. Connect again when ready.")
    "login_required", "interaction_required" -> ProviderException(ProviderFailureKind.AUTH, "ChatGPT needs you to sign in again. Connect again.")
    "server_error", "temporarily_unavailable" -> ProviderException(ProviderFailureKind.NETWORK, "ChatGPT sign-in is temporarily unavailable. Try again later.")
    else -> ProviderException(ProviderFailureKind.AUTH, "ChatGPT sign-in was not completed. Connect again.")
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
