package com.section11.listingforge.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (Proof Key for Code Exchange) primitives.
 *
 * The verifier is a high-entropy random string we keep server-side. Its
 * SHA-256, base64url-encoded, is the "challenge" we send to Etsy in the
 * authorize URL. At token-exchange time we present the original verifier; Etsy
 * re-hashes it and confirms it matches the challenge it saw earlier. That round
 * trip is what lets a client authenticate WITHOUT a shared secret â€” the entire
 * reason the OAuth flow here is zero-secret.
 */
object Pkce {
    private val random = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    /** ~86 chars, comfortably inside the spec's 43â€“128 range. */
    fun newVerifier(): String {
        val bytes = ByteArray(64)
        random.nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }

    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return urlEncoder.encodeToString(digest)
    }

    /** Opaque random value echoed by Etsy on callback; defends against CSRF. */
    fun newState(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }
}
