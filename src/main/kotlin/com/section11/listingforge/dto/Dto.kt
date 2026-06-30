package com.section11.listingforge.dto

import kotlinx.serialization.Serializable

/** Uniform JSON error shape returned by the StatusPages handler. */
@Serializable
data class ErrorResponse(val error: String)

/**
 * GET /api/shop: the signed-in seller's shop, trimmed to what a client needs.
 * The BFF resolves this by pivoting from getMe's shop_id to the shop resource,
 * so the client gets one flat object instead of two Etsy payloads.
 */
@Serializable
data class ShopResponse(val id: Long, val name: String)
