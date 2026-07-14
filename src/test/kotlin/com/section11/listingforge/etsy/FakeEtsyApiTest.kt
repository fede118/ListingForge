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

    @Test
    fun `getTaxonomy flattens the canned nested mock into every node`() = runTest {
        val nodes = FakeEtsyApi().getTaxonomy()

        assertEquals(10, nodes.size)
        val digitalPatterns = nodes.single { it.id == 111L }
        assertEquals("Digital Patterns", digitalPatterns.name)
        assertEquals(
            "Craft Supplies & Tools > Patterns & How To > Digital Patterns",
            digitalPatterns.path,
        )
    }
}
