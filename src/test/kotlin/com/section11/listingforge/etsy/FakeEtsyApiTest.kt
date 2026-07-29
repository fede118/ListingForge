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

    @Test
    fun `getListings filters the canned mock to the requested state`() = runTest {
        val page = FakeEtsyApi().getListings("ignored-user", state = "draft", limit = 25, offset = 0)

        assertEquals(3, page.count)
        assertEquals(3, page.listings.size)
        assertEquals(setOf("draft"), page.listings.map { it.state }.toSet())
    }

    @Test
    fun `getListings maps price, thumbnail and editUrl the same way live mode would`() = runTest {
        val page = FakeEtsyApi().getListings("ignored-user", state = "draft", limit = 25, offset = 0)

        val sakura = page.listings.single { it.listingId == 555000101L }
        assertEquals("Sakura Blossom Seamless Pattern", sakura.title)
        assertEquals("4.50", sakura.price)
        assertEquals("https://www.etsy.com/your/shops/me/listing-editor/edit/555000101", sakura.editUrl)
        assertEquals("https://i.etsystatic.com/mock/170x135/sakura.jpg", sakura.thumbnailUrl)

        val noPhotoYet = page.listings.single { it.listingId == 555000103L }
        assertEquals(null, noPhotoYet.thumbnailUrl)
    }

    @Test
    fun `getListings pages the filtered results by limit and offset`() = runTest {
        val page = FakeEtsyApi().getListings("ignored-user", state = "draft", limit = 1, offset = 1)

        assertEquals(3, page.count)
        assertEquals(1, page.listings.size)
        assertEquals(555000102L, page.listings.single().listingId)
    }
}
