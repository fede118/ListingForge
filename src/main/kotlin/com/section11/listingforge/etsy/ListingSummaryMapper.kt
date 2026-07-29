package com.section11.listingforge.etsy

import com.section11.listingforge.dto.ListingSummaryResponse
import java.math.BigDecimal

/**
 * Maps one Etsy listing-summary row (Task 12's browse-drafts read) to the
 * client-facing DTO. Shared by EtsyApiClient (live) and FakeEtsyApi (mock) so
 * both produce identically shaped output - the same reason flattenTaxonomy
 * is its own shared function rather than duplicated in each implementation.
 */
internal fun EtsyListingSummary.toResponse() = ListingSummaryResponse(
    listingId = listingId,
    title = title,
    state = state,
    price = price.toDecimalString(),
    quantity = quantity,
    editUrl = "$ETSY_LISTING_EDITOR_BASE/$listingId",
    thumbnailUrl = images.firstOrNull()?.url170x135,
)

/**
 * Converts Etsy's integer-over-divisor money type to the plain decimal-string
 * convention every other price field in this codebase already uses
 * (ListingRequest.price, TemplateRequest.price) - so the client's price
 * formatting/parsing doesn't need a second convention for read vs. write.
 * The divisor is always a power of ten, so moving the decimal point left by
 * one less than its digit count is exact (no floating-point rounding).
 */
internal fun EtsyMoney.toDecimalString(): String {
    val decimalPlaces = divisor.toString().length - 1
    return BigDecimal(amount).movePointLeft(decimalPlaces).toPlainString()
}
