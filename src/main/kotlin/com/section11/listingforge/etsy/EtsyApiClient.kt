package com.section11.listingforge.etsy

import com.section11.listingforge.auth.OAuthClient
import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.dto.ShopResponse
import com.section11.listingforge.dto.TaxonomyNodeResponse
import com.section11.listingforge.error.NotAuthenticatedException
import com.section11.listingforge.token.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Etsy's custom app-identity header. Not part of HttpHeaders (which is why it's a
 * named const here rather than a typed constant), and required on every Etsy API
 * call alongside the bearer token.
 */
private const val ETSY_API_KEY_HEADER = "x-api-key"

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
    private val oauth: OAuthClient,
    private val tokenStore: TokenStore,
    private val config: AppConfig,
) : EtsyApi {
    private val base = config.etsy.apiBase
    private val apiKeyHeader: String
        get() = "${config.etsy.keystring}:${config.etsy.sharedSecret}"

    // Taxonomy is Etsy-wide, not per-seller, and changes rarely, so the first
    // caller's result is cached for the life of the process. The mutex only
    // guards the fetch-and-populate race; reads of an already-populated cache
    // never touch it.
    private val taxonomyCacheLock = Mutex()
    private var cachedTaxonomy: List<TaxonomyNodeResponse>? = null

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
    override suspend fun getMe(userId: String): String {
        val token = validAccessToken(userId)
        return http.get("$base/users/me") { etsyAuth(token) }.bodyAsText()
    }

    /**
     * getMe carries the shop_id, so resolving a shop is two hops: read it, then
     * fetch the shop by id for its name. A signed-in user with no shop (shopId
     * null) is unexpected under the shops_r scope, so it surfaces as a 500 rather
     * than a fabricated empty shop.
     */
    override suspend fun getShop(userId: String): ShopResponse {
        val token = validAccessToken(userId)
        val me: EtsyUser = http.get("$base/users/me") { etsyAuth(token) }.body()
        val shopId = me.shopId
            ?: error("Signed-in Etsy user has no shop")
        val shop: EtsyShop = http.get("$base/shops/$shopId") { etsyAuth(token) }.body()
        return ShopResponse(id = shop.shopId, name = shop.shopName)
    }

    /**
     * Proxies GET /seller-taxonomy/nodes. Unlike the other calls, Etsy takes
     * only the app key here - no bearer token, hence no `validAccessToken`
     * call - so this works even for a caller with no stored Etsy token yet.
     */
    override suspend fun getTaxonomy(): List<TaxonomyNodeResponse> {
        cachedTaxonomy?.let { return it }
        return taxonomyCacheLock.withLock {
            cachedTaxonomy ?: fetchTaxonomy().also { cachedTaxonomy = it }
        }
    }

    private suspend fun fetchTaxonomy(): List<TaxonomyNodeResponse> {
        val response: EtsyTaxonomyResponse = http.get("$base/seller-taxonomy/nodes") {
            header(ETSY_API_KEY_HEADER, apiKeyHeader)
        }.body()
        return flattenTaxonomy(response.results)
    }

    /** Attaches the bearer token and app key every Etsy API call needs. */
    private fun HttpRequestBuilder.etsyAuth(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(ETSY_API_KEY_HEADER, apiKeyHeader)
    }
}
