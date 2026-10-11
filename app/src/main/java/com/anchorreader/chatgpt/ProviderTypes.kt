package com.anchorreader.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class ChatGptAccountState(
    val connected: Boolean = false,
    val planEnabled: Boolean = false,
    val accountId: String? = null,
    val label: String? = null,
    val signingIn: Boolean = false,
    val message: String? = null,
    val storageNeedsReset: Boolean = false,
)

enum class ProviderFailureKind { AUTH, NO_PLAN, LIMIT, NETWORK, INVALID_RESPONSE }

class ProviderException : Exception {
    val kind: ProviderFailureKind
    internal val streamReason: ProviderStreamReason?

    constructor(kind: ProviderFailureKind, message: String) : super(message) {
        this.kind = kind
        streamReason = null
    }

    internal constructor(kind: ProviderFailureKind, message: String, streamReason: ProviderStreamReason) : super(message) {
        this.kind = kind
        this.streamReason = streamReason
    }
}

internal fun interface ProviderClock { fun millis(): Long }

internal data class ProviderEndpoints(
    val issuer: String = "https://auth.openai.com",
    val authorize: String = "https://auth.openai.com/api/accounts/authorize",
    val token: String = "https://auth.openai.com/api/accounts/oauth/token",
    val discovery: String = "https://auth.openai.com/.well-known/openid-configuration",
    val jwks: String = "https://auth.openai.com/.well-known/jwks.json",
    val resource: String = "https://api.openai.com/v1",
) {
    val models: String get() = "$resource/models"
    val responses: String get() = "$resource/responses"
}

internal data class Registration(
    val clientId: String,
    val subject: String? = null,
    val issuer: String? = null,
    val email: String? = null,
    val idToken: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val scopes: Set<String> = emptySet(),
    val expiresAt: Long = 0,
    val nonce: String? = null,
) {
    val accountId: String? get() = subject?.let { digestId("$issuer\u0000$clientId\u0000$it") }
    val planEnabled: Boolean get() = "chatgpt.tokens.use.direct" in scopes && !accessToken.isNullOrEmpty()
    fun withoutTokens() = copy(idToken = null, accessToken = null, refreshToken = null, scopes = emptySet(), expiresAt = 0, nonce = null)
    fun json(): JSONObject = JSONObject().put("clientId", clientId).put("subject", subject)
        .put("issuer", issuer).put("email", email).put("idToken", idToken).put("accessToken", accessToken)
        .put("refreshToken", refreshToken).put("scopes", JSONArray(scopes.sorted()))
        .put("expiresAt", expiresAt).put("nonce", nonce)

    companion object {
        fun read(json: JSONObject) = Registration(
            json.getString("clientId"), json.nullableString("subject"), json.nullableString("issuer"),
            json.nullableString("email"), json.nullableString("idToken"), json.nullableString("accessToken"),
            json.nullableString("refreshToken"), json.optJSONArray("scopes")?.let { array ->
                (0 until array.length()).map { array.getString(it) }.toSet()
            } ?: emptySet(), json.optLong("expiresAt"), json.nullableString("nonce"),
        )
    }
}

internal data class ConnectionData(
    val hostId: String,
    val selectedClientId: String? = null,
    val registrations: List<Registration> = emptyList(),
) {
    fun selected() = registrations.find { it.clientId == selectedClientId }
    fun replace(record: Registration, select: Boolean = false) = copy(
        selectedClientId = if (select) record.clientId else selectedClientId,
        registrations = registrations.filterNot { it.clientId == record.clientId } + record,
    )
}

internal interface CredentialStore {
    fun load(): ConnectionData
    fun save(data: ConnectionData)
    fun recoveryData(): ConnectionData? = null
    fun resetForReconnect(): ConnectionData = throw IllegalStateException("Connection reset is unavailable.")
}

internal fun JSONObject.nullableString(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
internal fun randomValue(bytes: Int = 32): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.let(::base64Url)
internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
internal fun pkceChallenge(verifier: String) = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
internal fun digestId(value: String) = base64Url(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

internal const val PLAN_SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
internal const val CALLBACK_PATH = "/auth/callback"
