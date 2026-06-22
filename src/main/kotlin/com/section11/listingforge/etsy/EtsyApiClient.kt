package com.section11.listingforge.etsy

import com.section11.listingforge.auth.EtsyOAuthClient
import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.error.NotAuthenticatedException
import com.section11.listingforge.token.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.time.Instant

/**
 * Makes authenticated calls to Etsy on a user's behalf.
 *
 * Callers pass a userId. This class loads that user's token, transparently
 * refreshes it if expired, persists the refreshed token, and attaches the
 * right headers. None of this is visible to the browser â€” the BFF is the only
 * thing that ever holds a token or speaks to Etsy.
 *
 * DELIBERATE OPEN QUESTION lives here: does `x-api-key` need the keystring
 * alone, or `keystring:shared_secret`? Etsy's docs contradict themselves. We
 * currently send `keystring:shared_secret`; confirm with a real call (Phase 3)
 * and switch `apiKeyHeader` to the keystring alone if it 401/403s.
 */
class EtsyApiClient(
    private val http: HttpClient,
    private val oauth: EtsyOAuthClient,
    private val tokenStore: TokenStore,
    private val config: AppConfig,
) {
    private val base = config.etsyApiBase
    private val apiKeyHeader: String
        get() = "${config.etsyKeystring}:${config.etsySharedSecret}"

    /** Returns a non-expired access token, refreshing + persisting if needed. */
    private suspend fun validAccessToken(userId: String): String {
        val record = tokenStore.get(userId)
            ?: throw NotAuthenticatedException("Not signed in")
        if (Instant.now().isBefore(record.expiresAt)) return record.accessToken

        val refreshed = oauth.refresh(record.refreshToken)
        tokenStore.save(refreshed)
        return refreshed.accessToken
    }

    /** Proxies GET /users/me â€” the simplest call that proves a token works. */
    suspend fun getMe(userId: String): String {
        val token = validAccessToken(userId)
        return http.get("$base/users/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("x-api-key", apiKeyHeader)
        }.bodyAsText()
    }
}
