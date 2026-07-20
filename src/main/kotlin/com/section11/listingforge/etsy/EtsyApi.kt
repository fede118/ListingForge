package com.section11.listingforge.etsy

import com.section11.listingforge.dto.ListingFileResponse
import com.section11.listingforge.dto.ListingImageResponse
import com.section11.listingforge.dto.ListingRequest
import com.section11.listingforge.dto.ListingResponse
import com.section11.listingforge.dto.ShopResponse
import com.section11.listingforge.dto.TaxonomyNodeResponse

/**
 * The set of Etsy calls the BFF proxies, as an interface so the upstream can be
 * swapped at the composition root. EtsyApiClient is the real implementation;
 * FakeEtsyApi returns canned responses for mock mode. Routes depend only on
 * this type and never know which one they got.
 */
interface EtsyApi {
    /** Proxies GET /users/me. Returns the raw JSON body. */
    suspend fun getMe(userId: String): String

    /**
     * Resolves the signed-in user's shop (id + name). Aggregates two Etsy calls
     * â€” getMe for the shop_id, then GET /shops/{shop_id} for the name â€” into one
     * client-facing object, which is the BFF's first real value-add over a plain
     * proxy. Unlike getMe this returns a parsed DTO, not raw JSON.
     */
    suspend fun getShop(userId: String): ShopResponse

    /**
     * Proxies GET /seller-taxonomy/nodes, flattened into a single list with a
     * human-readable `path` per node. The taxonomy is global, not per-seller,
     * and Etsy only requires the app key for it (no OAuth token) - so unlike
     * the other calls here, this one isn't scoped to a userId.
     */
    suspend fun getTaxonomy(): List<TaxonomyNodeResponse>

    /**
     * Task 9, step 1: proxies Etsy's createDraftListing for the caller's shop,
     * with `type=download` fixed here - this BFF only ever creates digital
     * listings. The draft state comes from Etsy's own default; nothing in this
     * interface can move a listing past draft (no publish method exists).
     */
    suspend fun createDraftListing(userId: String, listing: ListingRequest): ListingResponse

    /**
     * Task 9, step 2: proxies Etsy's uploadListingImage for one image of an
     * existing draft. `rank` is 1-based (1 = primary photo) and absolute, so a
     * client retry that re-sends the same rank keeps the final ordering
     * correct. Throws ResourceNotFoundException if `listingId` doesn't exist or
     * belongs to another shop.
     */
    suspend fun uploadListingImage(
        userId: String,
        listingId: Long,
        image: ByteArray,
        filename: String,
        rank: Int,
    ): ListingImageResponse

    /**
     * Task 9, step 3: proxies Etsy's uploadListingFile to attach the
     * buyer-facing digital download (the generated zip) to an existing draft.
     * Throws ResourceNotFoundException if `listingId` doesn't exist or belongs
     * to another shop.
     */
    suspend fun uploadListingFile(userId: String, listingId: Long, file: ByteArray, filename: String): ListingFileResponse
}
