package com.section11.listingforge.etsy

import com.section11.listingforge.dto.ListingFileResponse
import com.section11.listingforge.dto.ListingImageResponse
import com.section11.listingforge.dto.ListingRequest
import com.section11.listingforge.dto.ListingResponse
import com.section11.listingforge.dto.ShopResponse
import com.section11.listingforge.dto.TaxonomyNodeResponse
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/**
 * Mock upstream. Returns canned success responses loaded from JSON files under
 * resources/mocks, so the app can hit the happy path with no real Etsy call,
 * no token, and no network. The userId is ignored on purpose: in mock
 * mode there's only one fixed demo user.
 *
 * Keep the JSON files shaped like the real Etsy responses (same field names);
 * that's what lets the app render mock and prod identically.
 */
class FakeEtsyApi : EtsyApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getMe(userId: String): String = loadMock("me.json")

    // Maps the canned shop.json the same way the real client maps Etsy's shop
    // response, so mock and prod produce an identical ShopResponse.
    override suspend fun getShop(userId: String): ShopResponse {
        val shop = json.decodeFromString<EtsyShop>(loadMock("shop.json"))
        return ShopResponse(id = shop.shopId, name = shop.shopName)
    }

    // Flattened once and reused: same "taxonomy barely changes" reasoning as
    // EtsyApiClient's cache, just without the concurrency concern a real
    // network call would have.
    private val taxonomy: List<TaxonomyNodeResponse> by lazy {
        val response = json.decodeFromString<EtsyTaxonomyResponse>(loadMock("taxonomy.json"))
        flattenTaxonomy(response.results)
    }

    override suspend fun getTaxonomy(): List<TaxonomyNodeResponse> = taxonomy

    // Task 9 fixtures: no real listing exists in mock mode, so these just hand
    // back incrementing ids that look like Etsy's rather than validating
    // anything about userId/listingId - there's nothing to 404 against.
    private val nextListingId = AtomicLong(FIRST_MOCK_LISTING_ID)
    private val nextImageId = AtomicLong(FIRST_MOCK_IMAGE_ID)
    private val nextFileId = AtomicLong(FIRST_MOCK_FILE_ID)

    override suspend fun createDraftListing(userId: String, listing: ListingRequest): ListingResponse {
        val listingId = nextListingId.getAndIncrement()
        return ListingResponse(
            listingId = listingId,
            state = "draft",
            editUrl = "$ETSY_LISTING_EDITOR_BASE/$listingId",
        )
    }

    override suspend fun uploadListingImage(
        userId: String,
        listingId: Long,
        image: ByteArray,
        filename: String,
        rank: Int,
    ): ListingImageResponse = ListingImageResponse(imageId = nextImageId.getAndIncrement(), rank = rank)

    override suspend fun uploadListingFile(
        userId: String,
        listingId: Long,
        file: ByteArray,
        filename: String,
    ): ListingFileResponse = ListingFileResponse(fileId = nextFileId.getAndIncrement())

    private fun loadMock(name: String): String =
        javaClass.getResourceAsStream("/mocks/$name")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Missing mock resource: /mocks/$name")
}

private const val FIRST_MOCK_LISTING_ID = 555000001L
private const val FIRST_MOCK_IMAGE_ID = 777000001L
private const val FIRST_MOCK_FILE_ID = 999000001L
