package com.anchorreader.chatgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProviderProtocolTest {
    @Test fun pkceUsesTheRfc7636VectorAndFreshSecrets() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val first = randomValue()
        assertTrue(first.length in 43..128)
        assertNotEquals(first, randomValue())
    }

    @Test fun actualLoopbackAcceptsOnlyTheMatchingPathAndState() {
        LoopbackCallbackServer("expected", 3_000).use { callback ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<AuthorizationCallback> { callback.await() }
                assertEquals(404, status(callback.redirectUri.replace(CALLBACK_PATH, "/other")))
                val connection = URL(callback.redirectUri + "?state=expected&code=secret-code&client_id=client-1").openConnection() as HttpURLConnection
                assertEquals(200, connection.responseCode)
                val html = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                assertTrue(html.contains("anchorreader://oauth-return"))
                assertFalse(html.contains("secret-code"))
                assertEquals(AuthorizationCallback("secret-code", "client-1"), result.get(3, TimeUnit.SECONDS))
                assertFailure(ProviderFailureKind.AUTH) { callback.await() }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun actualLoopbackRejectsWrongStateDuplicatesAndTimeout() {
        for (query in listOf("state=wrong&code=code", "state=expected&state=expected&code=code")) {
            LoopbackCallbackServer("expected", 2_000).use { callback ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val result = executor.submit<AuthorizationCallback> { callback.await() }
                    runCatching { status(callback.redirectUri + "?$query") }
                    assertTrue(runCatching { result.get(3, TimeUnit.SECONDS) }.isFailure)
                } finally { executor.shutdownNow() }
            }
        }
        LoopbackCallbackServer("state", 10).use { callback ->
            assertFailure(ProviderFailureKind.AUTH) { callback.await() }
        }
    }

    @Test fun rsaIdentityChecksIssuerAudienceNonceSignatureAndTime() {
        ProtocolFixture().use { fixture ->
            val verifier = IdTokenVerifier(fixture.http, fixture.endpoints, ProviderClock { fixture.now })
            val valid = fixture.claims("client", "nonce")
            assertEquals("subject", verifier.verify(fixture.sign(valid), "client", "nonce").subject)
            val invalid = listOf(
                JSONObject(valid.toString()).put("iss", "https://attacker.test"),
                JSONObject(valid.toString()).put("aud", "different-client"),
                JSONObject(valid.toString()).put("nonce", "different-nonce"),
                JSONObject(valid.toString()).put("exp", fixture.now / 1000 - 60),
                JSONObject(valid.toString()).put("iat", fixture.now / 1000 + 120),
                JSONObject(valid.toString()).put("aud", JSONArray(listOf("client", "other"))),
            )
            invalid.forEach { claims -> assertFailure(ProviderFailureKind.AUTH) { verifier.verify(fixture.sign(claims), "client", "nonce") } }
            val token = fixture.sign(valid)
            val parts = token.split('.').toMutableList()
            parts[1] = base64Url(JSONObject(valid.toString()).put("sub", "forged").toString().toByteArray())
            assertFailure(ProviderFailureKind.AUTH) { verifier.verify(parts.joinToString("."), "client", "nonce") }
            assertFailure(ProviderFailureKind.AUTH) { verifier.verify(fixture.sign(valid, algorithm = "none"), "client", "nonce") }
            assertFailure(ProviderFailureKind.AUTH) { verifier.verify(token, "client", "nonce", "another-subject") }
        }
    }

    @Test fun finalCompletedJsonIsRequiredAndPartialOrUnsafeStreamsAreRejected() {
        val final = completedEvent()
        assertEquals("{\"questions\":[]}", readCompletedResponse(final.byteInputStream()))
        val crlf = ": comment\r\n" + final.replace("\n", "\r\n")
        assertEquals("{\"questions\":[]}", readCompletedResponse(crlf.byteInputStream()))
        val failures = listOf(
            "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"{}\"}\n\n",
            final.removeSuffix("\n\n"), "data: [DONE]\n\n",
            event("response.incomplete", JSONObject()),
            event("response.refusal.done", JSONObject()),
            event("response.completed", JSONObject().put("response", finalResponse("not json"))),
            event("response.completed", JSONObject().put("response", finalResponse("{} trailing-data"))),
            event("response.completed", JSONObject().put("response", finalResponse().put("status", "incomplete"))),
            event("response.completed", JSONObject().put("response", finalResponse().put("output", JSONArray().put(JSONObject().put("type", "function_call"))))),
            "data: " + "x".repeat(64 * 1024 + 1) + "\n\n",
            ":" + "€".repeat(24 * 1024) + "\n\n" + final,
            (":" + "€".repeat(1024) + "\n\n").repeat(350) + final,
        )
        failures.forEach { text -> assertFailure(ProviderFailureKind.INVALID_RESPONSE) { readCompletedResponse(text.byteInputStream()) } }
        val failure = event("response.failed", JSONObject().put("response", JSONObject().put("error",
            JSONObject().put("code", "subscription_sharing_usage_limit_exceeded"))))
        assertFailure(ProviderFailureKind.LIMIT) { readCompletedResponse(failure.byteInputStream()) }
        val unavailable = event("response.failed", JSONObject().put("response", JSONObject().put("error",
            JSONObject().put("code", "subscription_sharing_usage_unavailable"))))
        assertFailure(ProviderFailureKind.NETWORK) { readCompletedResponse(unavailable.byteInputStream()) }
    }

    @Test fun systemBrowserRegistrationAndResponseUseTheDocumentedContract() = runBlocking {
        ProtocolFixture().use { fixture ->
            val store = MemoryStore()
            val provider = fixture.provider(store)
            val account = connect(provider, fixture)
            val query = fixture.browserQueries.single()
            assertEquals("dynamic_agent_client", query["client_id"])
            assertEquals("AnchorReader", query["agent_name_hint"])
            assertEquals(PLAN_SCOPES, query["scope"])
            assertEquals("S256", query["code_challenge_method"])
            assertEquals(store.value.hostId, query["ext_agent_host_id"])
            val exchange = fixture.tokenForms.single()
            assertEquals("client-1", exchange["client_id"])
            assertEquals(query["redirect_uri"], exchange["redirect_uri"])
            assertEquals(query["code_challenge"], pkceChallenge(exchange.getValue("code_verifier")))
            assertTrue(provider.state.value.connected)
            assertTrue(provider.state.value.planEnabled)
            assertEquals("{\"questions\":[]}", provider.response(account, "Make grounded questions.", "Page 1 source", JSONObject().put("type", "object")))
            val body = fixture.responseBodies.single()
            assertEquals("listed-model", body.getString("model"))
            assertFalse(body.getBoolean("store"))
            assertTrue(body.getBoolean("stream"))
            assertEquals("user", body.getJSONArray("input").getJSONObject(0).getString("role"))
            assertTrue(body.getJSONObject("text").getJSONObject("format").getBoolean("strict"))
            assertFalse(body.has("background") || body.has("max_output_tokens"))
            assertFailureSuspend(ProviderFailureKind.AUTH) { provider.response("wrong-account", "", "source", JSONObject()) }
        }
    }

    @Test fun malformedAndWrongShapeCatalogsRecordOnlyTheParseFailure() = runBlocking {
        for (body in listOf("FAKE_SECRET_CATALOG {broken", "{\"models\":\"FAKE_SECRET_WRONG_SHAPE\"}",
            "{\"models\":[{\"visibility\":\"list\",\"slug\":\"listed-model\"},\"FAKE_SECRET_NON_OBJECT\"]}")) {
            ProtocolFixture().use { fixture ->
                fixture.modelsBody = body
                val records = mutableListOf<ProviderFailureRecord>()
                val provider = fixture.provider(MemoryStore(), failureSink = ProviderFailureSink { records += it })
                val account = connect(provider, fixture)
                assertDiagnosticFailure(provider, account, records,
                    "stage=models_parse class=JSONException code=catalog_shape status=200")
                assertEquals(1, fixture.modelCalls.get())
                assertTrue(fixture.responseBodies.isEmpty())
            }
        }
    }

    @Test fun metadataRichCatalogAboveBothOldBoundsUsesTheFirstListedModel() = runBlocking {
        ProtocolFixture().use { fixture ->
            val models = JSONArray((0 until 300).map { index ->
                JSONObject().put("slug", when (index) { 240 -> "first-listed"; 241 -> "second-listed"; else -> "hidden-$index" })
                    .put("visibility", if (index in 240..241) "list" else "hidden")
                    .put("display_name", "Synthetic catalog model $index")
                    .put("description", "FAKE_SECRET_MODEL_METADATA " + "Generated fixture model description. ".repeat(32))
                    .put("capabilities", JSONObject().put("text", true).put("images", true).put("tools", true))
                    .put("limits", JSONObject().put("context_window", 128_000).put("output_tokens", 16_000))
            })
            val body = JSONObject().put("models", models).toString()
            assertTrue(models.length() > 256 && models.length() < 4096)
            assertTrue(body.toByteArray(Charsets.UTF_8).size in (256 * 1024 + 1) until (2 * 1024 * 1024))
            fixture.modelsBody = body
            val records = mutableListOf<ProviderFailureRecord>()
            val provider = fixture.provider(MemoryStore(), failureSink = ProviderFailureSink { records += it })
            val account = connect(provider, fixture)
            assertEquals("{\"questions\":[]}", provider.response(account, "FAKE_SECRET_INSTRUCTIONS", "FAKE_SECRET_SOURCE", JSONObject()))
            assertEquals(1, fixture.modelCalls.get())
            assertEquals(1, fixture.responseBodies.size)
            assertEquals("first-listed", fixture.responseBodies.single().getString("model"))
            assertTrue(records.isEmpty())
        }
    }

    @Test fun catalogByteBoundaryAcceptsExactlyTwoMiBUtf8AndRejectsOneExtraByte() = runBlocking {
        val byteCap = 2 * 1024 * 1024
        for (size in listOf(byteCap, byteCap + 1)) {
            ProtocolFixture().use { fixture ->
                val json = JSONObject().put("models", JSONArray().put(JSONObject().put("visibility", "list").put("slug", "boundary-listed")))
                val metadata = "FAKE_SECRET_UTF8_METADATA_" + "é".repeat(1024)
                val unpadded = json.put("fixture_metadata", metadata).toString()
                val paddingBytes = size - unpadded.toByteArray(Charsets.UTF_8).size
                val body = json.put("fixture_metadata", metadata + "é".repeat(paddingBytes / 2) + "p".repeat(paddingBytes % 2)).toString()
                assertEquals(size, body.toByteArray(Charsets.UTF_8).size)
                assertTrue(body.length < byteCap)
                fixture.modelsBody = body
                val records = mutableListOf<ProviderFailureRecord>()
                val provider = fixture.provider(MemoryStore(), failureSink = ProviderFailureSink { records += it })
                val account = connect(provider, fixture)
                if (size == byteCap) {
                    assertEquals("{\"questions\":[]}", provider.response(account, "", "source", JSONObject()))
                    assertEquals(1, fixture.responseBodies.size)
                    assertEquals("boundary-listed", fixture.responseBodies.single().getString("model"))
                    assertTrue(records.isEmpty())
                } else {
                    assertDiagnosticFailure(provider, account, records,
                        "stage=models_read class=IllegalArgumentException code=catalog_bound status=200")
                    assertTrue(fixture.responseBodies.isEmpty())
                }
                assertEquals(1, fixture.modelCalls.get())
            }
        }
    }

    @Test fun catalogCountBoundaryAccepts4096AndRejects4097BeforeInference() = runBlocking {
        for (count in listOf(4096, 4097)) {
            ProtocolFixture().use { fixture ->
                val models = JSONArray((0 until count).map { index -> JSONObject()
                    .put("visibility", if (index in 1..2) "list" else "hidden")
                    .put("slug", when (index) { 1 -> "first-listed"; 2 -> "second-listed"; else -> "hidden-$index" })
                })
                val body = JSONObject().put("models", models).toString()
                assertEquals(count, models.length())
                assertTrue(body.toByteArray(Charsets.UTF_8).size < 2 * 1024 * 1024)
                fixture.modelsBody = body
                val records = mutableListOf<ProviderFailureRecord>()
                val provider = fixture.provider(MemoryStore(), failureSink = ProviderFailureSink { records += it })
                val account = connect(provider, fixture)
                if (count == 4096) {
                    assertEquals("{\"questions\":[]}", provider.response(account, "", "source", JSONObject()))
                    assertEquals(1, fixture.responseBodies.size)
                    assertEquals("first-listed", fixture.responseBodies.single().getString("model"))
                    assertTrue(records.isEmpty())
                } else {
                    assertDiagnosticFailure(provider, account, records,
                        "stage=models_parse class=IllegalArgumentException code=other_failure status=200")
                    assertTrue(fixture.responseBodies.isEmpty())
                }
                assertEquals(1, fixture.modelCalls.get())
            }
        }
    }

    @Test fun noListedModelOrInvalidFirstListedSlugDoesNotFallBackOrStartInference() = runBlocking {
        for (models in listOf(
            JSONArray().put(JSONObject().put("visibility", "hidden").put("slug", "hidden-model")),
            JSONArray().put(JSONObject().put("visibility", "list").put("slug", "https://FAKE_SECRET_BAD_SLUG.test/model"))
                .put(JSONObject().put("visibility", "list").put("slug", "otherwise-valid")),
            JSONArray().put(JSONObject().put("visibility", "list").put("slug", "")),
        )) {
            ProtocolFixture().use { fixture ->
                fixture.modelsBody = JSONObject().put("models", models).toString()
                val records = mutableListOf<ProviderFailureRecord>()
                val provider = fixture.provider(MemoryStore(), failureSink = ProviderFailureSink { records += it })
                val account = connect(provider, fixture)
                assertDiagnosticFailure(provider, account, records,
                    "stage=models_parse class=ProviderException code=provider_failure status=200 failure_kind=no_plan",
                    "No ChatGPT model is available for this account.", ProviderFailureKind.NO_PLAN)
                assertEquals(1, fixture.modelCalls.get())
                assertTrue(fixture.responseBodies.isEmpty())
            }
        }
    }

    @Test fun modelAndResponsesConnectionFailuresHaveDistinctStagesWithoutStaleStatus() = runBlocking {
        for ((target, stage) in listOf("models" to "models_open", "responses" to "responses_open")) {
            ProtocolFixture().use { fixture ->
                val transport = object : ChatGptHttp {
                    override fun open(url: String, method: String, headers: Map<String, String>, body: ByteArray?): HttpReply {
                        if (url.endsWith("/$target")) throw IOException("FAKE_SECRET_URL $url FAKE_SECRET_TOKEN")
                        return fixture.http.open(url, method, headers, body)
                    }
                    override fun cancelAll() = fixture.http.cancelAll()
                }
                val records = mutableListOf<ProviderFailureRecord>()
                val provider = fixture.provider(MemoryStore(), transport, ProviderFailureSink { records += it })
                val account = connect(provider, fixture)
                assertDiagnosticFailure(provider, account, records, "stage=$stage class=IOException code=io_failure")
                assertTrue(fixture.responseBodies.isEmpty())
            }
        }
    }

    @Test fun replyCloseFailuresAreDistinctFromReadFailuresAndDoNotReturnCompletedQuestions() = runBlocking {
        for ((target, stage) in listOf("models" to "models_close", "responses" to "responses_close")) {
            ProtocolFixture().use { fixture ->
                val transport = object : ChatGptHttp {
                    override fun open(url: String, method: String, headers: Map<String, String>, body: ByteArray?): HttpReply {
                        val reply = fixture.http.open(url, method, headers, body)
                        if (!url.endsWith("/$target")) return reply
                        return HttpReply(reply.status, reply.headers, reply.input) {
                            reply.close()
                            throw IOException("FAKE_SECRET_CLOSE $url")
                        }
                    }
                    override fun cancelAll() = fixture.http.cancelAll()
                }
                val records = mutableListOf<ProviderFailureRecord>()
                val provider = fixture.provider(MemoryStore(), transport, ProviderFailureSink { records += it })
                val account = connect(provider, fixture)
                assertDiagnosticFailure(provider, account, records,
                    "stage=$stage class=IOException code=io_failure status=200" + if (target == "responses") " media=event_stream" else "")
                assertEquals(if (target == "models") 0 else 1, fixture.responseBodies.size)
            }
        }
    }

    @Test fun typedHttpFailureKeepsItsVisibleMessageAndNeverRecordsTheProviderBody() = runBlocking {
        ProtocolFixture().use { fixture ->
            fixture.modelsStatus = 503
            fixture.modelsBody = "{\"error\":{\"code\":\"FAKE_SECRET_CODE\",\"message\":\"FAKE_SECRET_PROVIDER_BODY\"}}"
            val records = mutableListOf<ProviderFailureRecord>()
            val provider = fixture.provider(MemoryStore(), failureSink = ProviderFailureSink { records += it })
            val account = connect(provider, fixture)
            assertDiagnosticFailure(provider, account, records,
                "stage=models_read class=ProviderException code=provider_failure status=503 failure_kind=network",
                "ChatGPT is unavailable. Try again later.")
        }
    }

    @Test fun successfulResponseEmitsNothingAndCancellationRemainsUnloggedAndRethrown() = runBlocking {
        ProtocolFixture().use { fixture ->
            val records = mutableListOf<ProviderFailureRecord>()
            var cancel = false
            val transport = object : ChatGptHttp {
                override fun open(url: String, method: String, headers: Map<String, String>, body: ByteArray?): HttpReply {
                    if (cancel && url.endsWith("/models")) throw kotlinx.coroutines.CancellationException("FAKE_SECRET_CANCEL")
                    return fixture.http.open(url, method, headers, body)
                }
                override fun cancelAll() = fixture.http.cancelAll()
            }
            val provider = fixture.provider(MemoryStore(), transport, ProviderFailureSink { records += it })
            val account = connect(provider, fixture)
            assertEquals("{\"questions\":[]}", provider.response(account, "FAKE_SECRET_INSTRUCTIONS", "FAKE_SECRET_SOURCE", JSONObject()))
            assertTrue(records.isEmpty())
            cancel = true
            val error = try { provider.response(account, "", "source", JSONObject()); null } catch (error: Exception) { error }
            assertTrue(error is kotlinx.coroutines.CancellationException)
            assertTrue(records.isEmpty())
        }
    }

    @Test fun aFailingDiagnosticSinkCannotReplaceTheSafeProviderFailure() = runBlocking {
        ProtocolFixture().use { fixture ->
            fixture.modelsBody = "FAKE_SECRET_CATALOG {broken"
            var calls = 0
            val provider = fixture.provider(MemoryStore(), failureSink = ProviderFailureSink {
                calls++
                throw IllegalStateException("FAKE_SECRET_SINK")
            })
            val account = connect(provider, fixture)
            val error = try { provider.response(account, "", "source", JSONObject()); null } catch (error: Exception) { error }
            assertTrue(error is ProviderException)
            assertEquals("ChatGPT request stopped. Try again.", error?.message)
            assertEquals(1, calls)
        }
    }

    @Test fun invalidExchangeKeepsTheIssuedClientAndNextSignInReusesIt() = runBlocking {
        ProtocolFixture().use { fixture ->
            val store = MemoryStore()
            val provider = fixture.provider(store)
            fixture.tokenStatus = 400
            assertFailureSuspend(ProviderFailureKind.INVALID_RESPONSE) { connect(provider, fixture) }
            assertEquals("client-1", store.value.selectedClientId)
            assertNull(store.value.selected()?.accessToken)
            fixture.tokenStatus = 200
            connect(provider, fixture)
            assertEquals("client-1", fixture.browserQueries.last()["client_id"])
            assertFalse(fixture.browserQueries.last().containsKey("agent_name_hint"))
        }
    }

    @Test fun refreshIsSerializedAndRotatingTokensArePersisted() = runBlocking {
        ProtocolFixture().use { fixture ->
            val store = MemoryStore()
            val provider = fixture.provider(store)
            val account = connect(provider, fixture)
            fixture.now += 4_000_000
            val first = async(Dispatchers.IO) { provider.response(account, "", "source", JSONObject()) }
            val second = async(Dispatchers.IO) { provider.response(account, "", "source", JSONObject()) }
            first.await(); second.await()
            val refreshes = fixture.tokenForms.filter { it["grant_type"] == "refresh_token" }
            assertEquals(1, refreshes.size)
            assertEquals("refresh-1", refreshes.single()["refresh_token"])
            assertFalse(refreshes.single().containsKey("scope"))
            assertEquals("refresh-2", store.value.selected()?.refreshToken)
            assertEquals("client-1", refreshes.single()["client_id"])
        }
    }

    @Test fun canceledExchangeRevokesMintedTokensAndRetainsTheIssuedClient() = runBlocking {
        ProtocolFixture().use { fixture ->
            val store = MemoryStore()
            val provider = fixture.provider(store)
            fixture.beforeTokenSend = { provider.cancelSignIn() }
            assertFailureSuspend(ProviderFailureKind.AUTH) { connect(provider, fixture) }
            assertEquals("client-1", store.value.selectedClientId)
            assertNull(store.value.selected()?.accessToken)
            assertEquals("refresh-1", fixture.revocations.single()["token"])
            assertFalse(provider.state.value.signingIn)
        }
    }

    @Test fun explicitStorageResetRetainsPublicIdsAndRequiresFreshVerifiedSignIn() = runBlocking {
        ProtocolFixture().use { fixture ->
            val metadata = ConnectionData("urn:uuid:fixture-host", "client-1", listOf(Registration("client-1")))
            val store = object : CredentialStore {
                var value = metadata
                var broken = true
                override fun load(): ConnectionData { check(!broken); return value }
                override fun save(data: ConnectionData) { check(!broken); value = data }
                override fun recoveryData() = metadata
                override fun resetForReconnect(): ConnectionData { broken = false; value = metadata; return value }
            }
            val provider = fixture.provider(store)
            assertFailureSuspend(ProviderFailureKind.AUTH) { connect(provider, fixture) }
            assertTrue(provider.state.value.storageNeedsReset)
            assertFalse(provider.state.value.connected)
            assertTrue(fixture.browserQueries.isEmpty())
            provider.resetConnection()
            assertFalse(provider.state.value.storageNeedsReset)
            assertEquals(metadata, store.value)
            connect(provider, fixture)
            assertEquals("client-1", fixture.browserQueries.single()["client_id"])
            assertTrue(provider.state.value.connected)
            assertEquals("subject", store.value.selected()?.subject)
        }
    }

    @Test fun sourceBoundsAllowJsonWrappersButRejectOversizedPayloadsBeforeInference() = runBlocking {
        ProtocolFixture().use { fixture ->
            val provider = fixture.provider(MemoryStore())
            val account = connect(provider, fixture)
            val wrapped = JSONObject().put("pages", JSONArray().put(JSONObject().put("page", 1).put("text", "\"".repeat(12_000)))).toString()
            assertTrue(wrapped.length > 12_000)
            provider.response(account, "", wrapped, JSONObject())
            assertEquals(1, fixture.responseBodies.size)
            assertFailureSuspend(ProviderFailureKind.INVALID_RESPONSE) {
                provider.response(account, "", "x".repeat(128 * 1024 + 1), JSONObject())
            }
            assertEquals(1, fixture.responseBodies.size)
        }
    }

    @Test fun terminalRefreshClearsTokensWhileTransientFailureKeepsThem() = runBlocking {
        for ((status, code, cleared) in listOf(Triple(400, "invalid_grant", true), Triple(503, "unavailable", false))) {
            ProtocolFixture().use { fixture ->
                val store = MemoryStore()
                val provider = fixture.provider(store)
                val account = connect(provider, fixture)
                fixture.now += 4_000_000
                fixture.tokenStatus = status
                fixture.tokenError = code
                assertFailureSuspend(if (cleared) ProviderFailureKind.AUTH else ProviderFailureKind.NETWORK) {
                    provider.response(account, "", "source", JSONObject())
                }
                assertEquals(cleared, store.value.selected()?.refreshToken == null)
                assertEquals("client-1", store.value.selectedClientId)
            }
        }
    }

    @Test fun lateRefreshCannotRestoreAConnectionAfterSignOut() = runBlocking {
        ProtocolFixture().use { fixture ->
            val store = MemoryStore()
            val uncancelable = object : ChatGptHttp {
                override fun open(url: String, method: String, headers: Map<String, String>, body: ByteArray?) = fixture.http.open(url, method, headers, body)
                override fun cancelAll() = Unit
            }
            val provider = fixture.provider(store, uncancelable)
            val account = connect(provider, fixture)
            fixture.now += 4_000_000
            fixture.refreshEntered = CountDownLatch(1)
            fixture.releaseRefresh = CountDownLatch(1)
            val response = async(Dispatchers.IO) { runCatching { provider.response(account, "", "source", JSONObject()) } }
            assertTrue(fixture.refreshEntered!!.await(3, TimeUnit.SECONDS))
            provider.signOut()
            fixture.releaseRefresh!!.countDown()
            assertTrue(response.await().isFailure)
            assertFalse(provider.state.value.connected)
            assertNull(store.value.selected()?.refreshToken)
            assertEquals("client-1", store.value.selectedClientId)
            assertEquals(listOf("refresh-1", "refresh-2"), fixture.revocations.map { it["token"] })
        }
    }

    @Test fun diagnosticCallCapRemainsSpentAfterRejectedAccountChangeAndVerifiedSameAccountReconnect() = runBlocking {
        ProtocolFixture().use { fixture ->
            val store = MemoryStore()
            val responseMethods = CopyOnWriteArrayList<String>()
            val transport = object : ChatGptHttp {
                override fun open(url: String, method: String, headers: Map<String, String>, body: ByteArray?): HttpReply {
                    if (url == fixture.endpoints.responses) responseMethods += method
                    return fixture.http.open(url, method, headers, body)
                }
                override fun cancelAll() = fixture.http.cancelAll()
            }
            val provider = fixture.provider(store, transport, diagnosticResponsesCallLimit = 1)
            val firstAccount = connect(provider, fixture)
            assertEquals("subject", store.value.selected()?.subject)
            assertEquals("{\"questions\":[]}", provider.response(firstAccount, "", "source", JSONObject()))
            assertEquals(listOf("POST"), responseMethods.toList())
            assertEquals(1, fixture.responseBodies.size)

            provider.signOut()
            assertFalse(provider.state.value.connected)
            assertNull(store.value.selected()?.accessToken)
            assertNull(store.value.selected()?.refreshToken)
            assertEquals("refresh-1", fixture.revocations.single()["token"])

            fixture.subject = "different-subject"
            assertFailureSuspend(ProviderFailureKind.AUTH) { connect(provider, fixture) }
            assertFalse(provider.state.value.connected)
            assertEquals("subject", store.value.selected()?.subject)
            assertNull(store.value.selected()?.accessToken)
            assertNull(store.value.selected()?.refreshToken)
            assertEquals(listOf("refresh-1", "refresh-1"), fixture.revocations.map { it["token"] })

            fixture.subject = "subject"
            val secondAccount = connect(provider, fixture)
            assertEquals(firstAccount, secondAccount)
            assertTrue(provider.state.value.connected)
            assertTrue(provider.state.value.planEnabled)
            assertEquals("subject", store.value.selected()?.subject)
            assertEquals(3, fixture.browserQueries.size)
            assertEquals(3, fixture.tokenForms.count { it["grant_type"] == "authorization_code" })

            val failure = try { provider.response(secondAccount, "", "source", JSONObject()); null } catch (error: Exception) { error }
            assertTrue(failure is ProviderException)
            assertEquals(ProviderFailureKind.INVALID_RESPONSE, (failure as ProviderException).kind)
            assertEquals("Generation stopped. Your book and ready questions are saved.", failure.message)
            assertEquals(2, fixture.modelCalls.get())
            assertEquals(listOf("POST"), responseMethods.toList())
            assertEquals(1, fixture.responseBodies.size)
        }
    }

    @Test fun releaseTransportRejectsArbitraryOriginsPlaintextAndRedirects() {
        val production = PlatformChatGptHttp()
        assertTrue(runCatching { production.open("http://auth.openai.com/api/accounts/oauth/token") }.isFailure)
        assertTrue(runCatching { production.open("https://attacker.test/token") }.isFailure)
        assertTrue(runCatching { production.open("https://auth.openai.com@attacker.test/token") }.isFailure)
        ProtocolFixture().use { fixture ->
            fixture.http.open(fixture.origin + "/redirect").use { reply ->
                assertEquals(302, reply.status)
            }
            assertEquals(0, fixture.redirectTargetCalls)
        }
    }

    private fun assertFailure(kind: ProviderFailureKind, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected ProviderException, got $failure", failure is ProviderException)
        assertEquals(kind, (failure as ProviderException).kind)
    }

    private suspend fun assertFailureSuspend(kind: ProviderFailureKind, block: suspend () -> Unit) {
        val failure = try { block(); null } catch (error: Exception) { error }
        assertTrue("Expected ProviderException, got $failure", failure is ProviderException)
        assertEquals(kind, (failure as ProviderException).kind)
    }

    private suspend fun assertDiagnosticFailure(provider: ChatGptProvider, account: String,
        records: List<ProviderFailureRecord>, expected: String, message: String = "ChatGPT request stopped. Try again.",
        kind: ProviderFailureKind = ProviderFailureKind.NETWORK) {
        val error = try {
            provider.response(account, "FAKE_SECRET_INSTRUCTIONS", "FAKE_SECRET_SOURCE", JSONObject().put("description", "FAKE_SECRET_SCHEMA"))
            null
        } catch (error: Exception) { error }
        assertTrue(error is ProviderException)
        assertEquals(kind, (error as ProviderException).kind)
        assertEquals(message, error.message)
        assertEquals(1, records.size)
        assertEquals(expected, records.single().line())
        assertFalse(records.single().line().contains("FAKE_SECRET"))
        assertFalse(records.single().line().contains("http" + "://"))
        assertFalse(records.single().line().contains(account))
    }

    private suspend fun connect(provider: ChatGptProvider, fixture: ProtocolFixture): String = provider.signIn { url ->
        val query = parseQuery(URI(url).rawQuery)
        fixture.browserQueries += query
        fixture.nonce = query.getValue("nonce")
        Thread({ status(query.getValue("redirect_uri") + "?state=${query.getValue("state")}&code=fixture-code&client_id=client-1") },
            "fixture-browser-return").apply { isDaemon = true; start() }
    }
}

private fun status(url: String): Int = (URL(url).openConnection() as HttpURLConnection).let { connection ->
    try { connection.connectTimeout = 2_000; connection.readTimeout = 2_000; connection.responseCode }
    finally { connection.disconnect() }
}

private class MemoryStore : CredentialStore {
    @Volatile var value = ConnectionData("urn:uuid:fixture-installation")
    override fun load() = value
    override fun save(data: ConnectionData) { value = data }
}

private class ProtocolFixture : AutoCloseable {
    private val server = LocalHttpFixture()
    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "provider-fixture").apply { isDaemon = true } }
    val origin = "http://127.0.0.1:${server.address.port}"
    val endpoints = ProviderEndpoints(origin, "$origin/authorize", "$origin/token", "$origin/discovery", "$origin/jwks", "$origin/v1")
    val http = PlatformChatGptHttp(setOf(origin)) { url -> url.openConnection() as HttpURLConnection }
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    @Volatile var now = 1_800_000_000_000L
    @Volatile var nonce = "nonce"
    @Volatile var subject = "subject"
    @Volatile var tokenStatus = 200
    @Volatile var tokenError = "invalid_grant"
    @Volatile var refreshEntered: CountDownLatch? = null
    @Volatile var releaseRefresh: CountDownLatch? = null
    @Volatile var beforeTokenSend: (() -> Unit)? = null
    @Volatile var redirectTargetCalls = 0
    @Volatile var modelsBody: String? = null
    @Volatile var modelsStatus = 200
    val modelCalls = AtomicInteger()
    val tokenForms = CopyOnWriteArrayList<Map<String, String>>()
    val browserQueries = CopyOnWriteArrayList<Map<String, String>>()
    val responseBodies = CopyOnWriteArrayList<JSONObject>()
    val revocations = CopyOnWriteArrayList<Map<String, String>>()
    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                when (exchange.requestURI.path) {
                    "/jwks" -> {
                        val key = pair.public as RSAPublicKey
                        exchange.json(JSONObject().put("keys", JSONArray().put(JSONObject().put("kty", "RSA").put("kid", "fixture-key")
                            .put("use", "sig").put("alg", "RS256").put("n", base64Url(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()))
                            .put("e", base64Url(key.publicExponent.toByteArray())))))
                    }
                    "/token" -> {
                        val form = parseQuery(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                        tokenForms += form
                        val refresh = form["grant_type"] == "refresh_token"
                        if (refresh) { refreshEntered?.countDown(); releaseRefresh?.await(5, TimeUnit.SECONDS) }
                        if (tokenStatus != 200) exchange.json(JSONObject().put("error", tokenError), tokenStatus)
                        else {
                            beforeTokenSend?.invoke()
                            exchange.json(JSONObject().put("access_token", if (refresh) "access-2" else "access-1")
                            .put("refresh_token", if (refresh) "refresh-2" else "refresh-1").put("token_type", "Bearer")
                            .put("expires_in", 3600).put("scope", PLAN_SCOPES).put("id_token", sign(claims("client-1", nonce))))
                        }
                    }
                    "/v1/models" -> {
                        modelCalls.incrementAndGet()
                        exchange.send(modelsStatus, "application/json", modelsBody ?: JSONObject().put("models", JSONArray()
                        .put(JSONObject().put("visibility", "hidden").put("slug", "hidden-model"))
                        .put(JSONObject().put("visibility", "list").put("slug", "listed-model"))).toString())
                    }
                    "/v1/responses" -> {
                        responseBodies += JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                        exchange.send(200, "text/event-stream", completedEvent())
                    }
                    "/discovery" -> exchange.json(JSONObject().put("issuer", origin).put("revocation_endpoint", "$origin/revoke"))
                    "/revoke" -> { revocations += parseQuery(exchange.requestBody.readBytes().toString(Charsets.UTF_8)); exchange.send(200, "application/json", "") }
                    "/redirect" -> { exchange.responseHeaders.add("Location", "$origin/redirect-target"); exchange.send(302, "application/json", "{}") }
                    "/redirect-target" -> { redirectTargetCalls++; exchange.json(JSONObject()) }
                    else -> exchange.json(JSONObject(), 404)
                }
            } finally { exchange.close() }
        }
        server.start()
    }
    fun provider(store: CredentialStore, transport: ChatGptHttp = http, failureSink: ProviderFailureSink = ProviderFailureSink {},
        diagnosticResponsesCallLimit: Int = 0) =
        ChatGptProvider(store, transport, endpoints, ProviderClock { now }, Dispatchers.Unconfined, failureSink,
            diagnosticResponsesCallLimit = diagnosticResponsesCallLimit)
    fun claims(client: String, nonce: String) = JSONObject().put("iss", origin).put("sub", subject).put("aud", client)
        .put("iat", now / 1000).put("exp", now / 1000 + 3600).put("nonce", nonce).put("email", "reader@example.test")
    fun sign(claims: JSONObject, algorithm: String = "RS256"): String {
        val header = base64Url(JSONObject().put("alg", algorithm).put("kid", "fixture-key").toString().toByteArray())
        val payload = base64Url(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update("$header.$payload".toByteArray(Charsets.US_ASCII)) }.sign()
        return "$header.$payload.${base64Url(signature)}"
    }
    override fun close() { releaseRefresh?.countDown(); http.cancelAll(); server.stop(); executor.shutdownNow() }
}

private fun LocalExchange.json(json: JSONObject, status: Int = 200) = send(status, "application/json", json.toString())
private fun LocalExchange.send(status: Int, contentType: String, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    responseHeaders.set("Content-Type", contentType)
    sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
private fun finalResponse(text: String = "{\"questions\":[]}") = JSONObject().put("status", "completed")
    .put("output", JSONArray().put(JSONObject().put("type", "message").put("role", "assistant")
        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))))
private fun event(type: String, data: JSONObject): String = "event: $type\ndata: ${data.put("type", type)}\n\n"
private fun completedEvent() = event("response.completed", JSONObject().put("response", finalResponse()))
