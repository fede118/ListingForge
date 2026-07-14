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

/**
 * A single node in Etsy's category taxonomy, flattened from Etsy's nested
 * tree. `path` is the full breadcrumb ("Parent > Child > Node"), built
 * server-side so the client can do flat text search without walking a tree.
 */
@Serializable
data class TaxonomyNodeResponse(val id: Long, val name: String, val path: String)

/** GET /api/taxonomy: every node of Etsy's seller taxonomy, flattened. */
@Serializable
data class TaxonomyResponse(val nodes: List<TaxonomyNodeResponse>)
