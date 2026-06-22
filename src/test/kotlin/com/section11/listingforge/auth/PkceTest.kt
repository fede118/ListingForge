package com.section11.listingforge.auth

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PkceTest {

    @Test
    fun `verifier length is within the PKCE spec range`() {
        val verifier = Pkce.newVerifier()
        assertTrue(verifier.length in 43..128, "verifier length was ${verifier.length}")
    }

    @Test
    fun `challenge is the base64url-encoded sha256 of the verifier`() {
        val verifier = Pkce.newVerifier()
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        assertEquals(expected, Pkce.challengeFor(verifier))
    }

    @Test
    fun `each verifier is unique`() {
        assertNotEquals(Pkce.newVerifier(), Pkce.newVerifier())
    }

    @Test
    fun `each state is unique`() {
        assertNotEquals(Pkce.newState(), Pkce.newState())
    }
}
