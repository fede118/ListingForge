package com.section11.listingforge.auth

import com.section11.listingforge.token.TokenRecord
import java.time.Instant

/**
 * Mock upstream for the OAuth token exchange. Returns a canned TokenRecord with
 * NO network call, so MOCK mode can run the full sign-in flow (stub consent ->
 * /auth/callback -> session/bearer) without a real Etsy app or credentials.
 *
 * userId is fixed to "mock-user" - the same id FakeEtsyApi already uses - so the
 * two fakes agree on who is signed in: mock mode has exactly one demo user, not
 * one per fake code. The `code` and `verifier` parameters are accepted (to match
 * OAuthClient) but ignored; there is nothing to validate against in mock mode,
 * since PendingAuthStore already did the real state/verifier bookkeeping before
 * this is ever called.
 */
class FakeOAuthClient(
    private val demoUserId: String = "mock-user",
) : OAuthClient {

    override suspend fun exchangeCode(code: String, verifier: String): TokenRecord = fakeRecord()

    override suspend fun refresh(refreshToken: String): TokenRecord = fakeRecord()

    private fun fakeRecord() = TokenRecord(
        userId = demoUserId,
        // Prefixed with the userId + '.' to mirror the real Etsy token shape
        // (EtsyOAuthClient derives userId from this same prefix), even though
        // FakeOAuthClient never needs to parse it back out.
        accessToken = "$demoUserId.fake-access-token",
        refreshToken = "fake-refresh-token",
        expiresAt = Instant.now().plusSeconds(3600),
    )
}
