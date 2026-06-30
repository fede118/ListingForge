package com.section11.listingforge.etsy

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeEtsyApiTest {

    @Test
    fun `getShop maps the canned shop mock to id and name`() = runTest {
        val shop = FakeEtsyApi().getShop("ignored-user")
        assertEquals(987654321, shop.id)
        assertEquals("Demo Shop", shop.name)
    }
}
