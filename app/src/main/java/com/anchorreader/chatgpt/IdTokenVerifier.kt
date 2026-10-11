package com.anchorreader.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

internal data class VerifiedIdentity(val subject: String, val email: String?)

/** Trust only the fixed issuer's JWKS. JWT URLs and algorithms cannot select a key. */
internal class IdTokenVerifier(
    private val http: ChatGptHttp,
    private val endpoints: ProviderEndpoints,
    private val clock: ProviderClock,
) {
    private var keys = emptyMap<String, RSAPublicKey>()
    private var loadedAt = 0L

    @Synchronized
    fun verify(token: String, clientId: String, nonce: String?, expectedSubject: String? = null): VerifiedIdentity {
        try {
            require(token.length <= 32 * 1024)
            val parts = token.split('.')
            require(parts.size == 3 && parts.all { it.isNotEmpty() })
            val decoder = Base64.getUrlDecoder()
            val header = JSONObject(decoder.decode(parts[0]).toString(Charsets.UTF_8))
            require(header.getString("alg") == "RS256" && !header.has("crit"))
            val kid = header.getString("kid")
            require(kid.length in 1..256)
            if (keys.isEmpty() || clock.millis() - loadedAt > 60 * 60 * 1000L) loadKeys()
            if (kid !in keys) loadKeys()
            val key = keys[kid] ?: throw IllegalArgumentException("Unknown signing key.")
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initVerify(key)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            }
            require(signature.verify(decoder.decode(parts[2])))
            val claims = JSONObject(decoder.decode(parts[1]).toString(Charsets.UTF_8))
            require(claims.getString("iss") == endpoints.issuer)
            val audiences = when (val audience = claims.get("aud")) {
                is String -> listOf(audience)
                is JSONArray -> (0 until audience.length()).map { audience.getString(it) }
                else -> throw IllegalArgumentException("Invalid token audience.")
            }
            require(clientId in audiences)
            if (audiences.size > 1 || claims.has("azp")) require(claims.getString("azp") == clientId)
            val now = clock.millis() / 1000
            val expiry = integralClaim(claims, "exp")
            val issued = integralClaim(claims, "iat")
            require(expiry > now - 30 && issued <= now + 30 && expiry > issued)
            if (claims.has("nbf")) require(integralClaim(claims, "nbf") <= now + 30)
            val tokenNonce = claims.nullableString("nonce")
            if (nonce != null) require(tokenNonce != null && sameSecret(tokenNonce, nonce))
            val subject = claims.getString("sub")
            require(subject.length in 1..512 && (expectedSubject == null || subject == expectedSubject))
            return VerifiedIdentity(subject, claims.nullableString("email")?.takeIf { it.length <= 320 })
        } catch (error: ProviderException) {
            throw error
        } catch (error: Exception) {
            throw ProviderException(ProviderFailureKind.AUTH, "ChatGPT sign-in could not be verified. Connect again.")
        }
    }

    private fun loadKeys() {
        http.open(endpoints.jwks).use { reply ->
            if (reply.status !in 200..299) throw providerHttpError(reply)
            val array = JSONObject(readBounded(reply.input, 256 * 1024)).getJSONArray("keys")
            require(array.length() in 1..64)
            val next = mutableMapOf<String, RSAPublicKey>()
            for (index in 0 until array.length()) {
                val key = array.getJSONObject(index)
                if (key.optString("kty") != "RSA" || (key.has("use") && key.getString("use") != "sig") ||
                    (key.has("alg") && key.getString("alg") != "RS256")) continue
                val decoder = Base64.getUrlDecoder()
                val modulus = BigInteger(1, decoder.decode(key.getString("n")))
                val exponent = BigInteger(1, decoder.decode(key.getString("e")))
                require(modulus.bitLength() in 2048..8192 && exponent >= BigInteger.valueOf(3))
                val kid = key.getString("kid")
                require(kid.length in 1..256 && kid !in next)
                next[kid] = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
            }
            require(next.isNotEmpty())
            keys = next
            loadedAt = clock.millis()
        }
    }

    private fun integralClaim(claims: JSONObject, key: String): Long {
        val value = claims.get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }
}

internal fun sameSecret(first: String, second: String): Boolean =
    MessageDigest.isEqual(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))
