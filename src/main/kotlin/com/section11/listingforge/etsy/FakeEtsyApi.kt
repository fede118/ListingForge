package com.section11.listingforge.etsy

import com.section11.listingforge.dto.ShopResponse
import kotlinx.serialization.json.Json

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

    private fun loadMock(name: String): String =
        javaClass.getResourceAsStream("/mocks/$name")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Missing mock resource: /mocks/$name")
}
