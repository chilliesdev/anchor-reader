package com.anchorreader.chatgpt

import android.content.Context
import com.anchorreader.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Public construction always uses OpenAI endpoints, platform TLS, and Android Keystore. */
class ChatGptProvider internal constructor(
    private val store: CredentialStore,
    private val http: ChatGptHttp,
    private val endpoints: ProviderEndpoints = ProviderEndpoints(),
    private val clock: ProviderClock = ProviderClock { System.currentTimeMillis() },
    private val browserDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val failureSink: ProviderFailureSink = AndroidProviderFailureSink,
    private val diagnosticResponsesCallLimit: Int = 0,
) {
    constructor(context: Context) : this(KeystoreCredentialStore(context.applicationContext), PlatformChatGptHttp(),
        diagnosticResponsesCallLimit = BuildConfig.DIAGNOSTIC_RESPONSES_CALL_LIMIT)

    init { require(diagnosticResponsesCallLimit in 0..1) }

    private val mutableState = MutableStateFlow(ChatGptAccountState())
    val state: StateFlow<ChatGptAccountState> = mutableState.asStateFlow()
    private val stateLock = Mutex()
    private val refreshLock = Mutex()
    private val responseLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authVersion = AtomicLong()
    private val requestVersion = AtomicLong()
    private val diagnosticResponseReserved = AtomicBoolean()
    private val verifier = IdTokenVerifier(http, endpoints, clock)
    @Volatile private var callbackServer: LoopbackCallbackServer? = null
    private lateinit var data: ConnectionData
    private var sessionVersion = 0L
    private var signingOut = false
    private var storageNeedsReset = false
    private val ready = scope.async {
        stateLock.withLock {
            try {
                data = store.load()
                publish()
            } catch (_: Exception) {
                storageNeedsReset = true
                val retained = runCatching { store.recoveryData() }.getOrNull()
                if (retained != null) {
                    data = retained
                    publish(message = "ChatGPT connection storage needs repair. Reset the connection, then connect again.")
                } else {
                    mutableState.value = ChatGptAccountState(storageNeedsReset = true,
                        message = "ChatGPT connection storage could not open. Reset the connection to try again.")
                    throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT connection storage needs repair.")
                }
            }
        }
    }

    suspend fun signIn(openBrowser: (String) -> Unit): String = withContext(Dispatchers.IO) {
        ready.await()
        val (attempt, returning) = stateLock.withLock {
            if (storageNeedsReset) throw ProviderException(ProviderFailureKind.AUTH, "Reset the ChatGPT connection, then connect again.")
            if (signingOut || mutableState.value.signingIn) throw ProviderException(ProviderFailureKind.AUTH, "Finish the current ChatGPT connection step first.")
            mutableState.value = mutableState.value.copy(signingIn = true, message = null)
            authVersion.incrementAndGet() to data.selected()
        }
        val state = randomValue()
        val nonce = randomValue()
        val codeVerifier = randomValue()
        val listener = try { LoopbackCallbackServer(state) } catch (_: Exception) {
            stateLock.withLock { publish(signingIn = false, message = "The sign-in listener could not start. Try again.") }
            throw ProviderException(ProviderFailureKind.NETWORK, "The sign-in listener could not start. Try again.")
        }
        callbackServer = listener
        var exchangedRefresh: String? = null
        var exchangedClient: String? = null
        var committed = false
        try {
            val fields = linkedMapOf(
                "client_id" to (returning?.clientId ?: "dynamic_agent_client"),
                "ext_agent_host_id" to data.hostId,
                "response_type" to "code", "redirect_uri" to listener.redirectUri,
                "scope" to PLAN_SCOPES, "resource" to endpoints.resource,
                "state" to state, "nonce" to nonce,
                "code_challenge_method" to "S256", "code_challenge" to pkceChallenge(codeVerifier),
            )
            if (returning == null) fields["agent_name_hint"] = "AnchorReader"
            val authorizeUrl = "${endpoints.authorize}?${formBody(fields).toString(Charsets.UTF_8)}"
            withContext(browserDispatcher) { openBrowser(authorizeUrl) }
            val callback = listener.await()
            val clientId = returning?.clientId ?: callback.clientId
                ?: throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT did not return a client registration.")
            if (clientId == "dynamic_agent_client" || (callback.clientId != null && callback.clientId != clientId)) {
                throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT returned a different client registration.")
            }
            stateLock.withLock {
                // Persist the issued registration before exchange; canceled exchanges can reuse it.
                if (returning == null && data.registrations.none { it.clientId == clientId }) {
                    persist(data.replace(Registration(clientId), select = data.selectedClientId == null))
                }
                checkAuthAttempt(attempt)
            }
            val tokens = tokenRequest(linkedMapOf(
                "grant_type" to "authorization_code", "client_id" to clientId, "code" to callback.code,
                "code_verifier" to codeVerifier, "redirect_uri" to listener.redirectUri, "resource" to endpoints.resource,
            ))
            exchangedRefresh = tokens.nullableString("refresh_token")
            exchangedClient = clientId
            val idToken = tokens.getString("id_token")
            val identity = verifier.verify(idToken, clientId, nonce, returning?.subject)
            val next = registrationFromTokens(tokens,
                Registration(clientId, identity.subject, endpoints.issuer, identity.email, nonce = nonce), idToken,
                requireRefresh = true)
            stateLock.withLock {
                checkAuthAttempt(attempt)
                sessionVersion++
                requestVersion.incrementAndGet()
                persist(data.replace(next, select = true))
                committed = true
                publish(signingIn = false)
            }
            next.accountId ?: throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT identity is missing.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = safeFailure(error, ProviderFailureKind.AUTH, "ChatGPT sign-in did not finish. Connect again.")
            stateLock.withLock { if (attempt == authVersion.get()) publish(signingIn = false, message = failure.message) }
            throw failure
        } finally {
            listener.close()
            if (callbackServer === listener) callbackServer = null
            withContext(NonCancellable) {
                val abandonedClient = exchangedClient
                val abandonedRefresh = exchangedRefresh
                if (!committed && abandonedRefresh != null && abandonedClient != null) {
                    runCatching { revoke(abandonedClient, abandonedRefresh) }
                }
                stateLock.withLock { if (attempt == authVersion.get()) publish(signingIn = false, message = mutableState.value.message) }
            }
        }
    }

    fun cancelSignIn() {
        authVersion.incrementAndGet()
        callbackServer?.close()
        callbackServer = null
        mutableState.value = mutableState.value.copy(signingIn = false, message = "ChatGPT sign-in canceled.")
    }

    suspend fun resetConnection() = withContext(Dispatchers.IO) {
        runCatching { ready.await() }
        cancelSignIn()
        cancelRequests()
        stateLock.withLock {
            if (!storageNeedsReset) return@withContext
            sessionVersion++
            try {
                data = store.resetForReconnect()
                storageNeedsReset = false
                publish(message = "ChatGPT connection reset. Connect again to continue.")
            } catch (_: Exception) {
                throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT connection storage could not be repaired. Try again.")
            }
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        ready.await()
        cancelSignIn()
        cancelRequests()
        val current = stateLock.withLock {
            if (signingOut) return@withContext
            signingOut = true
            sessionVersion++
            mutableState.value = ChatGptAccountState(message = "Disconnecting ChatGPT.")
            data.selected()
        }
        var revoked = current?.refreshToken.isNullOrEmpty()
        try {
            current?.refreshToken?.let { token ->
                // One bounded attempt. Local disconnect must succeed when the network is unavailable.
                revoked = revoke(current.clientId, token)
            }
        } catch (_: Exception) {
            revoked = false
        } finally {
            withContext(NonCancellable) {
                stateLock.withLock {
                    val disconnected = data.registrations.map { it.withoutTokens() }
                    persist(data.copy(registrations = disconnected))
                    signingOut = false
                    publish(message = if (revoked) "ChatGPT disconnected." else "ChatGPT disconnected on this device. Remote sign-out could not be confirmed.")
                }
            }
        }
    }

    fun cancelRequests() {
        requestVersion.incrementAndGet()
        http.cancelAll()
    }

    suspend fun response(accountId: String, instructions: String, source: String, schema: JSONObject): String =
        withContext(Dispatchers.IO) {
            ready.await()
            if (source.isBlank() || source.toByteArray(Charsets.UTF_8).size > 128 * 1024 || instructions.length > 8_000 ||
                schema.toString().toByteArray(Charsets.UTF_8).size > 32 * 1024) {
                throw ProviderException(ProviderFailureKind.INVALID_RESPONSE, "This section is too large. Use a smaller section.")
            }
            val request = requestVersion.get()
            responseLock.withLock {
                var stage = ProviderRequestStage.SESSION_CHECK
                var httpStatus: Int? = null
                var streamMedia: ProviderStreamMedia? = null
                try {
                    checkRequest(request, accountId)
                    val current = usableRegistration(accountId)
                    checkRequest(request, accountId)
                    if (!current.planEnabled) throw ProviderException(ProviderFailureKind.NO_PLAN, "Allow ChatGPT plan use to make questions.")
                    val headers = mapOf("Authorization" to "Bearer ${current.accessToken}")
                    stage = ProviderRequestStage.MODELS_OPEN
                    val model = http.open(endpoints.models, headers = headers).use { reply ->
                        httpStatus = reply.status
                        stage = ProviderRequestStage.MODELS_READ
                        if (reply.status !in 200..299) throw providerHttpError(reply)
                        val catalogText = readBounded(reply.input, MODEL_CATALOG_MAX_BYTES)
                        stage = ProviderRequestStage.MODELS_PARSE
                        val catalog = JSONObject(catalogText).getJSONArray("models")
                        require(catalog.length() <= MODEL_CATALOG_MAX_ENTRIES)
                        val selected = (0 until catalog.length()).map { catalog.getJSONObject(it) }
                            .firstOrNull { it.optString("visibility") == "list" }?.getString("slug")
                            ?.takeIf { it.length in 1..128 && it.all { char -> char.isLetterOrDigit() || char in "._:-" } }
                            ?: throw ProviderException(ProviderFailureKind.NO_PLAN, "No ChatGPT model is available for this account.")
                        stage = ProviderRequestStage.MODELS_CLOSE
                        selected
                    }
                    stage = ProviderRequestStage.SESSION_CHECK
                    checkRequest(request, accountId)
                    stage = ProviderRequestStage.REQUEST_BUILD
                    val body = JSONObject().put("model", model).put("instructions", instructions)
                        .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", source)))
                        .put("store", false).put("stream", true)
                        .put("text", JSONObject().put("format", JSONObject().put("type", "json_schema")
                            .put("name", "section_questions").put("strict", true).put("schema", schema)))
                    val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
                    if (bodyBytes.size > 256 * 1024) throw ProviderException(ProviderFailureKind.INVALID_RESPONSE, "This section is too large. Use a smaller section.")
                    stage = ProviderRequestStage.RESPONSES_OPEN
                    httpStatus = null
                    if (diagnosticResponsesCallLimit == 1 && !diagnosticResponseReserved.compareAndSet(false, true)) {
                        throw ProviderException(ProviderFailureKind.INVALID_RESPONSE,
                            "Generation stopped. Your book and ready questions are saved.")
                    }
                    val result = http.open(endpoints.responses, "POST", headers + ("Content-Type" to "application/json"),
                        bodyBytes).use { reply ->
                        httpStatus = reply.status
                        stage = ProviderRequestStage.RESPONSES_READ
                        if (reply.status !in 200..299) throw providerHttpError(reply)
                        val mediaType = reply.header("Content-Type")?.substringBefore(';')?.trim()
                        streamMedia = when {
                            mediaType.isNullOrEmpty() -> ProviderStreamMedia.MISSING
                            mediaType.matchesAsciiMediaType("text/event-stream") -> ProviderStreamMedia.EVENT_STREAM
                            mediaType.matchesAsciiMediaType("application/json") -> ProviderStreamMedia.JSON
                            mediaType.matchesAsciiMediaType("text/html") -> ProviderStreamMedia.HTML
                            else -> ProviderStreamMedia.OTHER
                        }
                        if (!mediaType.isNullOrEmpty() && !mediaType.matchesAsciiMediaType("text/event-stream")) {
                            throw ProviderException(ProviderFailureKind.INVALID_RESPONSE, "ChatGPT did not return a question stream.")
                        }
                        val completed = readCompletedResponse(reply.input)
                        stage = ProviderRequestStage.RESPONSES_CLOSE
                        completed
                    }
                    stage = ProviderRequestStage.FINISH
                    checkRequest(request, accountId)
                    result
                } catch (error: CancellationException) {
                    http.cancelAll()
                    throw error
                } catch (error: Exception) {
                    // A diagnostic sink must never replace the existing safe failure or affect retries.
                    ProviderFailureRecord.classify(stage, error, httpStatus, streamMedia)?.let { failure ->
                        runCatching { failureSink.record(failure) }
                    }
                    throw safeFailure(error, ProviderFailureKind.NETWORK, "ChatGPT request stopped. Try again.")
                }
            }
        }

    private suspend fun usableRegistration(accountId: String): Registration = refreshLock.withLock refresh@{
        val (current, version) = stateLock.withLock {
            val current = selectedAccount(accountId)
            current to sessionVersion
        }
        if (current.expiresAt > clock.millis() + 60_000 && !current.accessToken.isNullOrEmpty()) return@refresh current
        val refresh = current.refreshToken ?: throw ProviderException(ProviderFailureKind.AUTH, "Connect ChatGPT again to continue.")
        val tokens = try {
            tokenRequest(mapOf("grant_type" to "refresh_token", "client_id" to current.clientId,
                "refresh_token" to refresh, "resource" to endpoints.resource))
        } catch (error: TerminalRefreshException) {
            stateLock.withLock {
                if (sessionVersion == version && data.selectedClientId == current.clientId) {
                    persist(data.replace(current.withoutTokens()))
                    publish(message = "ChatGPT connection expired. Connect again.")
                }
            }
            throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT connection expired. Connect again.")
        }
        val replacementRefresh = tokens.nullableString("refresh_token")
        var committed = false
        try {
            val idToken = tokens.nullableString("id_token")
            if (idToken != null) {
                val claims = JSONObject(java.util.Base64.getUrlDecoder().decode(idToken.split('.')[1]).toString(Charsets.UTF_8))
                verifier.verify(idToken, current.clientId, if (claims.has("nonce")) current.nonce else null, current.subject)
            }
            val next = registrationFromTokens(tokens, current, idToken ?: current.idToken, requireRefresh = true)
            stateLock.withLock {
                if (sessionVersion != version || data.selectedClientId != current.clientId || signingOut) {
                    throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT account changed. Start again.")
                }
                persist(data.replace(next))
                committed = true
                publish()
                selectedAccount(accountId)
            }
        } finally {
            if (!committed && !replacementRefresh.isNullOrEmpty()) {
                withContext(NonCancellable) { runCatching { revoke(current.clientId, replacementRefresh) } }
            }
        }
    }

    private fun registrationFromTokens(tokens: JSONObject, identity: Registration, idToken: String?, requireRefresh: Boolean): Registration {
        require(!tokens.has("client_id") || tokens.getString("client_id") == identity.clientId)
        require(tokens.optString("token_type").equals("Bearer", ignoreCase = true))
        val access = tokens.getString("access_token").takeIf { it.length in 1..32 * 1024 } ?: throw IllegalArgumentException()
        val refresh = tokens.nullableString("refresh_token")?.takeIf { it.length in 1..32 * 1024 }
        require(!requireRefresh || refresh != null)
        val seconds = tokens.getLong("expires_in")
        require(seconds in 1..86_400)
        val scopes = if (tokens.has("scope")) tokens.getString("scope").split(' ').filter { it.isNotEmpty() }.toSet() else identity.scopes
        require(scopes.size <= 32)
        return identity.copy(idToken = idToken, accessToken = access, refreshToken = refresh,
            scopes = scopes, expiresAt = clock.millis() + seconds * 1000)
    }

    private fun tokenRequest(fields: Map<String, String>): JSONObject = http.open(endpoints.token, "POST",
        mapOf("Content-Type" to "application/x-www-form-urlencoded"), formBody(fields)).use { reply ->
        val json = runCatching { JSONObject(readBounded(reply.input, 128 * 1024)) }.getOrNull()
        if (reply.status !in 200..299) {
            val code = json?.opt("error")?.let { error -> if (error is JSONObject) error.optString("code") else error as? String }
                ?: json?.nullableString("code")
            if (fields["grant_type"] == "refresh_token" && code in TERMINAL_REFRESH_ERRORS) throw TerminalRefreshException()
            throw providerErrorCode(code, reply.status)
        }
        json ?: throw ProviderException(ProviderFailureKind.INVALID_RESPONSE, "ChatGPT returned invalid connection data.")
    }

    private fun revocationEndpoint(): String = http.open(endpoints.discovery).use { reply ->
        if (reply.status !in 200..299) throw providerHttpError(reply)
        val document = JSONObject(readBounded(reply.input, 128 * 1024))
        require(document.getString("issuer") == endpoints.issuer)
        val endpoint = document.getString("revocation_endpoint")
        val uri = URI(endpoint)
        val issuer = URI(endpoints.issuer)
        require(uri.scheme == issuer.scheme && uri.rawAuthority == issuer.rawAuthority && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.fragment == null && uri.path.startsWith('/') && uri.path.length < 256)
        endpoint
    }

    private fun revoke(clientId: String, token: String): Boolean {
        val endpoint = revocationEndpoint()
        val body = formBody(mapOf("token" to token, "token_type_hint" to "refresh_token", "client_id" to clientId))
        return http.open(endpoint, "POST", mapOf("Content-Type" to "application/x-www-form-urlencoded"), body).use { it.status == 200 }
    }

    private suspend fun checkRequest(request: Long, accountId: String) = stateLock.withLock {
        if (request != requestVersion.get()) throw ProviderException(ProviderFailureKind.NETWORK, "ChatGPT request canceled.")
        selectedAccount(accountId)
    }

    private fun selectedAccount(accountId: String): Registration {
        val current = data.selected()
        if (signingOut || current?.accountId != accountId || current.accessToken.isNullOrEmpty()) {
            throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT account changed. Connect again.")
        }
        return current
    }

    private fun checkAuthAttempt(attempt: Long) {
        if (attempt != authVersion.get() || signingOut) throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT sign-in canceled.")
    }

    private fun persist(next: ConnectionData) { store.save(next); data = next }

    private fun publish(signingIn: Boolean = mutableState.value.signingIn, message: String? = null) {
        val current = data.selected()
        val connected = current?.accountId != null && !current.accessToken.isNullOrEmpty() && !current.refreshToken.isNullOrEmpty()
        mutableState.value = ChatGptAccountState(connected, connected && current?.planEnabled == true,
            if (connected) current?.accountId else null, if (connected) current?.email ?: "ChatGPT account" else null,
            signingIn, message, storageNeedsReset)
    }

    private fun safeFailure(error: Exception, kind: ProviderFailureKind, message: String): ProviderException =
        error as? ProviderException ?: ProviderException(kind, message)

    private class TerminalRefreshException : Exception()

    companion object {
        private const val MODEL_CATALOG_MAX_BYTES = 2 * 1024 * 1024
        private const val MODEL_CATALOG_MAX_ENTRIES = 4096
        private val TERMINAL_REFRESH_ERRORS = setOf("invalid_grant", "invalid_refresh_token", "token_expired",
            "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")
    }
}

/** HTTP media type tokens fold ASCII letters only; Unicode lookalikes remain distinct. */
private fun String?.matchesAsciiMediaType(expected: String): Boolean = this != null && length == expected.length &&
    indices.all { index -> (if (this[index] in 'A'..'Z') this[index] + ('a' - 'A') else this[index]) == expected[index] }
