package com.section11.listingforge.etsy

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
    override suspend fun getMe(userId: String): String = loadMock("me.json")

    private fun loadMock(name: String): String =
        javaClass.getResourceAsStream("/mocks/$name")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Missing mock resource: /mocks/$name")
}
