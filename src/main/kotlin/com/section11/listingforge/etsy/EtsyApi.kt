package com.section11.listingforge.etsy

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
}
