package com.anchorreader.chatgpt

import android.content.ContextWrapper
import androidx.test.core.app.ActivityScenario
import com.anchorreader.MainActivity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Synthetic grant: actual Android Chrome, callback/provider/RSA and isolated Keystore. */
@RunWith(AndroidJUnit4::class)
class PhoneSignInDeviceTest {
    @Test fun nativeChromeVerifiesAndSavesSyntheticGrant() = verifyGrant(socketNoise = false)

    @Test fun nativeChromeVerifiesGrantAfterEmptyResetAndTimedOutSockets() = verifyGrant(socketNoise = true)

    @Test fun nativeChromeDenialNeverExchangesOrSavesTokens() = verifyGrant(socketNoise = false, denied = true)

    private fun verifyGrant(socketNoise: Boolean, denied: Boolean = false) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "phone-browser-fixture-${UUID.randomUUID()}")
        val alias = "anchorreader.chatgpt.phone.fixture.${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        val store = KeystoreCredentialStore(isolated, alias)
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        var nonce = "fixture-nonce"
        val tokenCalls = AtomicInteger()
        val jwksCalls = AtomicInteger()
        val endpoints = ProviderEndpoints()
        val socketNoiseResult = CompletableFuture<Unit>()
        val transport = object : ChatGptHttp {
            override fun open(url: String, method: String, headers: Map<String, String>, body: ByteArray?): HttpReply {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                val json = when (url) {
                    endpoints.token -> {
                        tokenCalls.incrementAndGet()
                        val claims = JSONObject().put("iss", endpoints.issuer).put("sub", "synthetic-subject")
                            .put("aud", "synthetic-client").put("nonce", nonce)
                            .put("iat", now / 1000).put("exp", now / 1000 + 3600)
                        val head = base64Url(JSONObject().put("alg", "RS256").put("kid", "synthetic-key").toString().toByteArray())
                        val payload = base64Url(claims.toString().toByteArray())
                        val signed = Signature.getInstance("SHA256withRSA").apply {
                            initSign(pair.private); update("$head.$payload".toByteArray(Charsets.US_ASCII))
                        }.sign()
                        JSONObject().put("access_token", "synthetic-access").put("refresh_token", "synthetic-refresh")
                            .put("token_type", "Bearer").put("expires_in", 3600).put("scope", PLAN_SCOPES)
                            .put("id_token", "$head.$payload.${base64Url(signed)}")
                    }
                    endpoints.jwks -> {
                        jwksCalls.incrementAndGet()
                        val key = pair.public as RSAPublicKey
                        JSONObject().put("keys", JSONArray().put(JSONObject().put("kty", "RSA").put("kid", "synthetic-key")
                            .put("alg", "RS256").put("use", "sig")
                            .put("n", base64Url(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()))
                            .put("e", base64Url(key.publicExponent.toByteArray()))))
                    }
                    else -> error("Unexpected fixture transport stage.")
                }
                return HttpReply(200, emptyMap(), json.toString().byteInputStream()) {}
            }
            override fun cancelAll() = Unit
        }
        val provider = ChatGptProvider(store, transport, endpoints, ProviderClock { now }, Dispatchers.Main.immediate,
            failureSink = ProviderFailureSink {}, completionSink = ProviderCompletionSink {})
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                supervisorScope {
                    val result = async(Dispatchers.IO) {
                        provider.signIn { authorize ->
                            assertEquals(Looper.getMainLooper(), Looper.myLooper())
                            val query = parseQuery(URI(authorize).rawQuery)
                            nonce = query.getValue("nonce")
                            val callback = query.getValue("redirect_uri") + "?state=" + query.getValue("state") +
                                if (denied) "&error=access_denied&error_description=PRIVATE_NATIVE_PROVIDER_TEXT" else "&code=synthetic-code&client_id=synthetic-client"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(callback))
                                .setPackage("com.android.chrome").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            if (socketNoise) {
                                // Android network calls belong off the UI thread, including loopback sockets.
                                Thread {
                                    try {
                                        val target = URI(query.getValue("redirect_uri"))
                                        Socket(target.host, target.port).close()
                                        Socket(target.host, target.port).use { it.setSoLinger(true, 0) }
                                        Socket(target.host, target.port).use { stalled ->
                                            stalled.getOutputStream().write("GET /auth/callback HTTP/1.1\r\n".toByteArray())
                                            stalled.getOutputStream().flush()
                                            context.startActivity(intent)
                                            stalled.soTimeout = 8_000
                                            assertEquals(-1, stalled.getInputStream().read())
                                        }
                                        socketNoiseResult.complete(Unit)
                                    } catch (error: Throwable) { socketNoiseResult.completeExceptionally(error) }
                                }.apply { isDaemon = true; start() }
                            } else { context.startActivity(intent) }
                        }
                    }
                    try {
                        if (denied) {
                            val failure = runCatching { withTimeout(30_000) { result.await() } }.exceptionOrNull()
                            assertTrue(failure is ProviderException)
                            assertEquals("ChatGPT sign-in was canceled or declined. Connect again when ready.", failure!!.message)
                            assertFalse(provider.state.value.connected)
                            assertFalse(provider.state.value.signingIn)
                            assertEquals(0, tokenCalls.get())
                            assertEquals(0, jwksCalls.get())
                            assertNull(KeystoreCredentialStore(isolated, alias).load().selected())
                            assertFalse(provider.state.value.message.orEmpty().contains("PRIVATE"))
                            return@supervisorScope
                        }
                        withTimeout(30_000) { result.await() }
                        if (socketNoise) socketNoiseResult.get(1, TimeUnit.SECONDS)
                        assertTrue(provider.state.value.connected)
                        assertTrue(provider.state.value.planEnabled)
                        assertEquals(1, tokenCalls.get())
                        assertEquals(1, jwksCalls.get())
                        val reopened = KeystoreCredentialStore(isolated, alias).load().selected()
                        assertEquals("synthetic-subject", reopened!!.subject)
                        assertEquals("synthetic-access", reopened.accessToken)
                    } finally { provider.cancelSignIn(); result.cancel() }
                }
            } finally {
                root.deleteRecursively()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            }
        }
    }

}
