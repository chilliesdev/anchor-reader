package com.anchorreader.chatgpt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext

/** Real task-owned HTTP/TLS sockets; no JDK module or permissive TLS verifier is needed. */
internal class LocalHttpFixture(ssl: SSLContext? = null) {
    private val server = ssl?.serverSocketFactory?.createServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        ?: ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val address = InetSocketAddress("127.0.0.1", server.localPort)
    var executor: Executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "local-http-fixture").apply { isDaemon = true } }
    private val handlers = linkedMapOf<String, (LocalExchange) -> Unit>()
    private val active = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var stopped = false

    fun createContext(path: String, handler: (LocalExchange) -> Unit) { handlers[path] = handler }
    fun start() {
        Thread({
            while (!stopped) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                active += socket
                executor.execute {
                    try {
                        socket.soTimeout = 5_000
                        val exchange = readExchange(socket)
                        val handler = handlers.entries.firstOrNull { exchange.requestURI.path.startsWith(it.key) }?.value
                        require(handler != null)
                        handler(exchange)
                    } catch (_: Exception) {
                        // Fixture clients can deliberately interrupt a request or reject its certificate.
                    } finally { active -= socket; socket.close() }
                }
            }
        }, "local-http-fixture-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped = true
        server.close()
        active.toList().forEach { runCatching { it.close() } }
        (executor as? ExecutorService)?.shutdownNow()
    }

    private fun readExchange(socket: Socket): LocalExchange {
        val input = socket.getInputStream()
        val header = ByteArrayOutputStream()
        var ending = 0
        while (ending < 4) {
            val byte = input.read()
            require(byte != -1 && header.size() < 16 * 1024)
            header.write(byte)
            ending = when {
                (ending == 0 || ending == 2) && byte == 13 -> ending + 1
                (ending == 1 || ending == 3) && byte == 10 -> ending + 1
                byte == 13 -> 1
                else -> 0
            }
        }
        val lines = header.toByteArray().toString(Charsets.US_ASCII).split("\r\n")
        val request = lines.first().split(' ')
        require(request.size == 3)
        val fields = lines.drop(1).filter { it.contains(':') }.associate {
            it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
        }
        val size = fields["content-length"]?.toInt() ?: 0
        require(size in 0..256 * 1024)
        val body = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(body, offset, size - offset)
            require(count > 0)
            offset += count
        }
        return LocalExchange(socket, URI(request[1]), ByteArrayInputStream(body), fields)
    }
}

internal class LocalExchange(
    private val socket: Socket,
    val requestURI: URI,
    val requestBody: InputStream,
    val requestHeaders: Map<String, String>,
) {
    val responseHeaders = FixtureHeaders()
    val responseBody: OutputStream get() = socket.getOutputStream()
    fun sendResponseHeaders(status: Int, length: Long) {
        val header = "HTTP/1.1 $status Fixture\r\nContent-Length: ${length.coerceAtLeast(0)}\r\nConnection: close\r\n" +
            responseHeaders.entries().joinToString("") { (key, value) -> "$key: $value\r\n" } + "\r\n"
        responseBody.write(header.toByteArray(Charsets.US_ASCII))
        responseBody.flush()
    }
    fun close() { socket.close() }
}

internal class FixtureHeaders {
    private val values = linkedMapOf<String, String>()
    fun set(key: String, value: String) { values[key] = value }
    fun add(key: String, value: String) { values[key] = value }
    fun entries() = values.entries
}
