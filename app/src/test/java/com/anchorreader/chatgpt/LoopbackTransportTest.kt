package com.anchorreader.chatgpt

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LoopbackTransportTest {
    @Test fun emptyAndResetSocketsDoNotLoseTheFollowingCallback() = withListener { callback, result ->
        val uri = URI(callback.redirectUri)
        Socket(uri.host, uri.port).close()
        Socket(uri.host, uri.port).use { it.setSoLinger(true, 0) }
        assertEquals(200, status(callback.redirectUri + "?state=expected&code=valid-code&client_id=client"))
        assertEquals(AuthorizationCallback("valid-code", "client"), result.get(3, TimeUnit.SECONDS))
    }

    @Test fun partialRequestTimeoutDoesNotLoseTheFollowingCallback() = withListener(8_000) { callback, result ->
        val uri = URI(callback.redirectUri)
        Socket(uri.host, uri.port).use { peer ->
            peer.getOutputStream().write("GET /auth/callback HTTP/1.1\r\n".toByteArray())
            peer.getOutputStream().flush()
            peer.soTimeout = 7_000
            assertEquals(-1, peer.getInputStream().read())
            assertFalse(result.isDone)
            assertEquals(200, status(callback.redirectUri + "?state=expected&code=after-timeout"))
            assertEquals("after-timeout", result.get(2, TimeUnit.SECONDS).code)
        }
    }

    @Test fun slowPartialRequestCannotExtendTheTotalAttemptDeadline() {
        LoopbackCallbackServer("expected", 250).use { callback ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val started = System.nanoTime()
                val result = executor.submit<AuthorizationCallback> { callback.await() }
                val uri = URI(callback.redirectUri)
                Socket(uri.host, uri.port).use { peer ->
                    repeat(8) {
                        runCatching { peer.getOutputStream().write('G'.code); peer.getOutputStream().flush() }
                        Thread.sleep(50)
                    }
                    val failure = runCatching { result.get(1, TimeUnit.SECONDS) }.exceptionOrNull()
                    assertEquals("ChatGPT sign-in timed out. Connect again.", (failure as ExecutionException).cause!!.message)
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500)
                }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun idleAcceptUsesTheTotalAttemptDeadline() {
        LoopbackCallbackServer("expected", 100).use { callback ->
            val started = System.nanoTime()
            val failure = runCatching { callback.await() }.exceptionOrNull()
            assertTrue(failure is ProviderException)
            assertEquals("ChatGPT sign-in timed out. Connect again.", failure!!.message)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 700)
        }
    }

    @Test fun replyWriteFailureKeepsAValidatedCallback() = withListener(writeFails = true) { callback, result ->
        sendWithoutReading(callback.redirectUri + "?state=expected&code=valid-code&client_id=client")
        assertEquals(AuthorizationCallback("valid-code", "client"), result.get(2, TimeUnit.SECONDS))
    }

    @Test fun replyWriteFailureCannotHideWrongStateOrProviderDenial() {
        for (query in listOf("state=wrong&code=code", "state=expected&error=access_denied")) {
            withListener(writeFails = true) { callback, result ->
                sendWithoutReading(callback.redirectUri + "?$query")
                val failure = runCatching { result.get(2, TimeUnit.SECONDS) }.exceptionOrNull() as ExecutionException
                assertTrue(failure.cause is ProviderException)
                assertEquals(if (query.startsWith("state=wrong")) "ChatGPT sign-in could not be verified. Connect again."
                    else "ChatGPT sign-in was canceled or declined. Connect again when ready.", failure.cause!!.message)
            }
        }
    }

    @Test fun unrelatedRequestsWithFailedRepliesDoNotAuthenticate() = withListener(writeFails = true) { callback, result ->
        sendWithoutReading(callback.redirectUri.replace(CALLBACK_PATH, "/favicon.ico"))
        assertFalse(result.isDone)
        sendWithoutReading(callback.redirectUri + "?state=expected&code=valid-code")
        assertEquals("valid-code", result.get(2, TimeUnit.SECONDS).code)
    }

    @Test fun malformedHostDuplicateAndEscapedQueryRemainRejectedAfterSocketNoise() {
        for (request in listOf(
            "GET $CALLBACK_PATH?state=expected&code=code HTTP/1.1\r\nHost: attacker.test\r\n\r\n",
            "GET $CALLBACK_PATH?state=expected&state=expected&code=code HTTP/1.1\r\nHost: HOST\r\n\r\n",
            "GET $CALLBACK_PATH?state=expected&code=%ZZ HTTP/1.1\r\nHost: HOST\r\n\r\n",
        )) {
            withListener { callback, result ->
                val uri = URI(callback.redirectUri)
                Socket(uri.host, uri.port).close()
                Socket(uri.host, uri.port).use { peer ->
                    peer.getOutputStream().write(request.replace("HOST", "${uri.host}:${uri.port}").toByteArray())
                    peer.getOutputStream().flush()
                    peer.soTimeout = 1_000
                    peer.getInputStream().readBytes()
                }
                val failure = runCatching { result.get(2, TimeUnit.SECONDS) }.exceptionOrNull() as ExecutionException
                assertTrue(failure.cause is ProviderException)
                assertEquals(ProviderFailureKind.AUTH, (failure.cause as ProviderException).kind)
            }
        }
    }

    @Test fun oauthGuidanceUsesOnlyKnownFixedMessages() {
        assertEquals(ProviderFailureKind.NETWORK, authorizationFailure("temporarily_unavailable").kind)
        assertEquals(ProviderFailureKind.AUTH, authorizationFailure("login_required").kind)
        val unknown = authorizationFailure("<script>PRIVATE_PROVIDER_TEXT</script>" + "x".repeat(20_000))
        assertEquals("ChatGPT sign-in was not completed. Connect again.", unknown.message)
        assertFalse(unknown.message!!.contains("PRIVATE"))
    }

    private fun withListener(timeout: Long = 3_000, writeFails: Boolean = false,
        block: (LoopbackCallbackServer, java.util.concurrent.Future<AuthorizationCallback>) -> Unit) {
        val callback = if (writeFails) LoopbackCallbackServer("expected", timeout) { _, _ -> throw IOException("PRIVATE_REPLY_ERROR") }
            else LoopbackCallbackServer("expected", timeout)
        callback.use {
            val executor = Executors.newSingleThreadExecutor()
            try { block(callback, executor.submit<AuthorizationCallback> { callback.await() }) }
            finally { executor.shutdownNow() }
        }
    }

    private fun sendWithoutReading(url: String) {
        val uri = URI(url)
        Socket(uri.host, uri.port).use { peer ->
            peer.getOutputStream().write("GET ${uri.rawPath}?${uri.rawQuery.orEmpty()} HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n\r\n".toByteArray())
            peer.getOutputStream().flush()
        }
    }

    private fun status(url: String): Int = (URL(url).openConnection() as HttpURLConnection).let { connection ->
        try { connection.connectTimeout = 1_000; connection.readTimeout = 2_000; connection.responseCode }
        finally { connection.disconnect() }
    }
}
