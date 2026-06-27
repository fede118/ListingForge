package com.section11.listingforge.auth

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mints and verifies the stateless bearer token used by non-browser clients
 * (the Android app) in place of the web session cookie.
 *
 * Format:  base64url("userId|expiresAtEpochSecond")  "."  base64url(HMAC-SHA256)
 *
 * The token carries only the Etsy userId and an expiry, HMAC-signed with the
 * same server secret that signs the web session cookie. It holds NO Etsy
 * credential: exactly like the cookie, it's an identity the client presents, and
 * the real tokens stay server-side in SQLite. The signature makes the userId
 * unforgeable; the expiry bounds the damage if a token leaks.
 */
class SessionTokenService(
    private val signKey: ByteArray,
    private val ttlSeconds: Long,
) {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun issue(userId: String, now: Instant = Instant.now()): String {
        val expiresAt = now.epochSecond + ttlSeconds
        val body = encoder.encodeToString("$userId|$expiresAt".toByteArray(Charsets.UTF_8))
        return "$body.${sign(body)}"
    }

    /**
     * Returns the userId iff the token is well-formed, correctly signed and
     * unexpired; null otherwise. Signature is checked before the payload is
     * trusted, in constant time.
     */
    fun verify(token: String, now: Instant = Instant.now()): String? {
        val dot = token.lastIndexOf('.')
        if (dot <= 0 || dot == token.length - 1) return null
        val body = token.substring(0, dot)
        val signature = token.substring(dot + 1)
        if (!constantTimeEquals(sign(body), signature)) return null

        val payload = try {
            String(decoder.decode(body), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val sep = payload.lastIndexOf('|')
        if (sep <= 0) return null
        val userId = payload.substring(0, sep)
        val expiresAt = payload.substring(sep + 1).toLongOrNull() ?: return null
        if (now.epochSecond >= expiresAt) return null
        return userId
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signKey, "HmacSHA256"))
        return encoder.encodeToString(mac.doFinal(body.toByteArray(Charsets.UTF_8)))
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
