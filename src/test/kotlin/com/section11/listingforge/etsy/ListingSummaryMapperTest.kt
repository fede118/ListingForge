package com.section11.listingforge.etsy

import kotlin.test.Test
import kotlin.test.assertEquals

class ListingSummaryMapperTest {

    @Test
    fun `toDecimalString converts amount over a cents divisor`() {
        assertEquals("4.50", EtsyMoney(amount = 450, divisor = 100).toDecimalString())
    }

    @Test
    fun `toDecimalString converts a divisor of 1 to a whole number`() {
        assertEquals("12", EtsyMoney(amount = 12, divisor = 1).toDecimalString())
    }

    @Test
    fun `toDecimalString keeps a trailing zero`() {
        assertEquals("5.00", EtsyMoney(amount = 500, divisor = 100).toDecimalString())
    }

    @Test
    fun `toResponse maps the primary image as the thumbnail and builds the editor url`() {
        val summary = EtsyListingSummary(
            listingId = 42,
            title = "Sakura Blossom Seamless Pattern",
            state = "draft",
            price = EtsyMoney(amount = 450, divisor = 100),
            quantity = 999,
            images = listOf(EtsyListingSummaryImage(listingImageId = 1, url170x135 = "https://example.test/1.jpg")),
        )

        val response = summary.toResponse()

        assertEquals(42, response.listingId)
        assertEquals("4.50", response.price)
        assertEquals("https://www.etsy.com/your/shops/me/listing-editor/edit/42", response.editUrl)
        assertEquals("https://example.test/1.jpg", response.thumbnailUrl)
    }

    @Test
    fun `toResponse leaves thumbnailUrl null when the listing has no images`() {
        val summary = EtsyListingSummary(
            listingId = 43,
            title = "No Photo Yet",
            state = "draft",
            price = EtsyMoney(amount = 400, divisor = 100),
            quantity = 999,
        )

        assertEquals(null, summary.toResponse().thumbnailUrl)
    }
}
