package com.section11.listingforge.etsy

import com.section11.listingforge.auth.OAuthClient
import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.dto.ListingFileResponse
import com.section11.listingforge.dto.ListingImageResponse
import com.section11.listingforge.dto.ListingRequest
import com.section11.listingforge.dto.ListingResponse
import com.section11.listingforge.dto.ShopResponse
import com.section11.listingforge.dto.TaxonomyNodeResponse
import com.section11.listingforge.error.EtsyUpstreamException
import com.section11.listingforge.error.InvalidRequestException
import com.section11.listingforge.error.NotAuthenticatedException
import com.section11.listingforge.error.ResourceNotFoundException
import com.section11.listingforge.token.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
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
        val shopId = resolveShopId(token)
        val shop: EtsyShop = http.get("$base/shops/$shopId") { etsyAuth(token) }.body()
        return ShopResponse(id = shop.shopId, name = shop.shopName)
    }

    /**
     * Task 9, step 1. `type` is fixed to ETSY_LISTING_TYPE_DOWNLOAD server-side -
     * the client never chooses it - and Etsy's default draft state is trusted
     * as-is; nothing here ever calls a publish endpoint. Tags are sent as
     * repeated `tags` form fields, Etsy's convention for array parameters on a
     * form-encoded body.
     */
    override suspend fun createDraftListing(userId: String, listing: ListingRequest): ListingResponse {
        val token = validAccessToken(userId)
        val shopId = resolveShopId(token)
        val response = http.submitForm(
            url = "$base/shops/$shopId/listings",
            formParameters = parameters {
                append("quantity", listing.quantity)
                append("title", listing.title)
                append("description", listing.description)
                append("price", listing.price)
                append("who_made", listing.whoMade)
                append("when_made", listing.whenMade)
                append("taxonomy_id", listing.taxonomyId.toString())
                append("type", ETSY_LISTING_TYPE_DOWNLOAD)
                listing.tags.forEach { append("tags", it) }
            }
        ) {
            etsyAuth(token)
            expectSuccess = false
        }
        val created = response.etsyBodyOrThrow<EtsyListing>()
        return ListingResponse(
            listingId = created.listingId,
            state = created.state,
            editUrl = "$ETSY_LISTING_EDITOR_BASE/${created.listingId}",
        )
    }

    /** Task 9, step 2. Ownership of `listingId` by the caller's shop is enforced by Etsy: a foreign or unknown id 404s. */
    override suspend fun uploadListingImage(
        userId: String,
        listingId: Long,
        image: ByteArray,
        filename: String,
        rank: Int,
    ): ListingImageResponse {
        val token = validAccessToken(userId)
        val shopId = resolveShopId(token)
        val response = http.submitFormWithBinaryData(
            url = "$base/shops/$shopId/listings/$listingId/images",
            formData = formData {
                append("rank", rank.toString())
                append(
                    "image",
                    image,
                    Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$filename\"") },
                )
            }
        ) {
            etsyAuth(token)
            expectSuccess = false
        }
        val uploaded = response.etsyBodyOrThrow<EtsyListingImage>("Listing $listingId not found")
        return ListingImageResponse(imageId = uploaded.listingImageId, rank = uploaded.rank)
    }

    /**
     * Task 9, step 3. Same ownership check as uploadListingImage - a foreign or unknown listingId
     * 404s. The file part carries an explicit `application/zip` content type: unlike images, an
     * untyped octet-stream part isn't reliably accepted by Etsy's file endpoint (observed as its
     * generic "an error occurred while uploading your file" 400), and the buyer download this
     * endpoint exists for is always the generated zip.
     */
    override suspend fun uploadListingFile(
        userId: String,
        listingId: Long,
        file: ByteArray,
        filename: String,
    ): ListingFileResponse {
        val token = validAccessToken(userId)
        val shopId = resolveShopId(token)
        val response = http.submitFormWithBinaryData(
            url = "$base/shops/$shopId/listings/$listingId/files",
            formData = formData {
                append("name", filename)
                append(
                    "file",
                    file,
                    Headers.build {
                        append(HttpHeaders.ContentType, ContentType.Application.Zip.toString())
                        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                    },
                )
            }
        ) {
            etsyAuth(token)
            expectSuccess = false
        }
        val uploaded = response.etsyBodyOrThrow<EtsyListingFile>("Listing $listingId not found")
        return ListingFileResponse(fileId = uploaded.listingFileId)
    }

    /** GET /users/me, just for its shop_id. Every shop-scoped call pivots through this. */
    private suspend fun resolveShopId(token: String): Long {
        val me: EtsyUser = http.get("$base/users/me") { etsyAuth(token) }.body()
        return me.shopId ?: error("Signed-in Etsy user has no shop")
    }

    /**
     * The shared response handling for Task 9's write calls: success parses the
     * body, a 404 (only meaningful when a listing id is in the URL) becomes
     * ResourceNotFoundException, a 400 passes Etsy's own message through as
     * InvalidRequestException, and anything else - most notably a 403 from a
     * token missing the listings_w scope - becomes EtsyUpstreamException so
     * StatusPages can surface it as a clean 502 instead of an unhandled 500.
     */
    private suspend inline fun <reified T> HttpResponse.etsyBodyOrThrow(notFoundMessage: String? = null): T = when {
        status.isSuccess() -> body()
        status == HttpStatusCode.NotFound && notFoundMessage != null -> throw ResourceNotFoundException(notFoundMessage)
        status == HttpStatusCode.BadRequest -> throw InvalidRequestException(etsyErrorMessage())
        else -> throw EtsyUpstreamException(status.value, etsyErrorMessage())
    }

    private suspend fun HttpResponse.etsyErrorMessage(): String {
        val body = runCatching { body<EtsyErrorResponse>() }.getOrNull()
        return body?.errorDescription ?: body?.error ?: "Etsy rejected the request"
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
