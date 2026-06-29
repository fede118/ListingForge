package com.section11.listingforge.etsy

/**
 * The set of Etsy calls the BFF proxies, as an interface so the upstream can be
 * swapped at the composition root. EtsyApiClient is the real implementation;
 * FakeEtsyApi returns canned responses for mock mode. Routes depend only on
 * this type and never know which one they got.
 */
interface EtsyApi {
    /** Proxies GET /users/me. Returns the raw JSON body. */
    suspend fun getMe(userId: String): String
}
