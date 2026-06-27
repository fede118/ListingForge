package com.section11.listingforge.auth

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionTokenServiceTest {

    private val key = "test-signing-key-that-is-long-enough".toByteArray()
    private fun service(ttl: Long = 3600) = SessionTokenService(key, ttl)

    @Test
    fun `issued token round-trips back to the userId`() {
        val svc = service()
        val token = svc.issue("1254198868")
        assertEquals("1254198868", svc.verify(token))
    }

    @Test
    fun `a tampered payload is rejected`() {
        val svc = service()
        val token = svc.issue("user-1")
        // Flip the last char of the payload (before the '.') -> signature no longer matches.
        val dot = token.indexOf('.')
        val mangled = token.substring(0, dot - 1) +
            (if (token[dot - 1] == 'A') 'B' else 'A') +
            token.substring(dot)
        assertNull(svc.verify(mangled))
    }

    @Test
    fun `a token signed with a different key is rejected`() {
        val token = service().issue("user-1")
        val other = SessionTokenService("a-completely-different-key-value".toByteArray(), 3600)
        assertNull(other.verify(token))
    }

    @Test
    fun `an expired token is rejected`() {
        val svc = service(ttl = 60)
        val issuedAt = Instant.now().minusSeconds(120) // already past its 60s TTL
        val token = svc.issue("user-1", now = issuedAt)
        assertNull(svc.verify(token))
    }

    @Test
    fun `malformed tokens are rejected, not thrown`() {
        val svc = service()
        assertNull(svc.verify(""))
        assertNull(svc.verify("no-dot"))
        assertNull(svc.verify("."))
        assertNull(svc.verify("only-trailing-dot."))
        assertNull(svc.verify("!!!not-base64!!!.signature"))
    }
}
