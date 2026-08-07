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

/**
 * POST/PUT /api/templates body: a saved listing-details form, mirroring the
 * client's ListingDetails exactly. `whoMade`/`whenMade` are opaque Etsy wire
 * values (e.g. "i_did") - this layer stores them as-is and never re-validates
 * Etsy's enums; that's the client's job. Only `name` (the template's own
 * label) is validated here.
 */
@Serializable
data class TemplateRequest(
    val name: String,
    val title: String,
    val description: String,
    val price: String,
    val quantity: String,
    val tags: List<String> = emptyList(),
    val whoMade: String,
    val whenMade: String,
    val taxonomyId: Long? = null,
    val taxonomyPath: String? = null,
    val specsText: String,
)

/** A stored template as returned to the client, with server-assigned id + timestamps. */
@Serializable
data class TemplateResponse(
    val id: Long,
    val name: String,
    val title: String,
    val description: String,
    val price: String,
    val quantity: String,
    val tags: List<String>,
    val whoMade: String,
    val whenMade: String,
    val taxonomyId: Long?,
    val taxonomyPath: String?,
    val specsText: String,
    val createdAt: String,
    val updatedAt: String,
)

/** GET /api/templates: every template saved for the active shop. */
@Serializable
data class TemplateListResponse(val templates: List<TemplateResponse>)

/**
 * POST /api/listings body: the Task 9 submit pipeline's first step. Mirrors
 * TemplateRequest minus the template-only `name`/`taxonomyPath`/`specsText`,
 * which never reach Etsy - same field names/types otherwise, so the client can
 * reuse its ListingDetails model for both. `price`/`quantity` stay strings (the
 * client pre-validates against Etsy's constraints); EtsyApiClient converts them
 * when it builds the Etsy form body.
 */
@Serializable
data class ListingRequest(
    val title: String,
    val description: String,
    val price: String,
    val quantity: String,
    val tags: List<String> = emptyList(),
    val whoMade: String,
    val whenMade: String,
    val taxonomyId: Long,
)

/**
 * POST /api/listings response: the newly created draft. `editUrl` is the Etsy
 * listing-editor page, surfaced by the client as the open-on-Etsy link.
 */
@Serializable
data class ListingResponse(val listingId: Long, val state: String, val editUrl: String)

/** POST /api/listings/{listingId}/images response. */
@Serializable
data class ListingImageResponse(val imageId: Long, val rank: Int)

/** POST /api/listings/{listingId}/file response. */
@Serializable
data class ListingFileResponse(val fileId: Long)

/**
 * GET /api/listings row: Task 12's browse-drafts read, trimmed to what the
 * client's list UI needs. `price` stays the same plain decimal-string
 * convention as ListingRequest/TemplateRequest (Etsy's own amount/divisor
 * money type is converted server-side - see EtsyMoney.toDecimalString).
 * `editUrl` is the same Etsy listing-editor page ListingResponse links to -
 * this is browse-only, so opening it is as far as this DTO goes.
 * `thumbnailUrl` is null when the listing has no images yet.
 */
@Serializable
data class ListingSummaryResponse(
    val listingId: Long,
    val title: String,
    val state: String,
    val price: String,
    val quantity: Int,
    val editUrl: String,
    val thumbnailUrl: String?,
)

/**
 * GET /api/listings response envelope. `count` is Etsy's total matching the
 * query (state filter applied) across all pages, not just this page's size -
 * it's what lets the client know whether to page again.
 */
@Serializable
data class ListingListResponse(val count: Int, val listings: List<ListingSummaryResponse>)

/**
 * GET /api/app/android: the About screen's download card (Task 15). Parsed
 * straight out of `deploy/deploy.sh`'s `app-metadata.json` sidecar - see
 * AppRoutes.kt for why this is read rather than computed per request.
 * `downloadPath` is the `/downloads/...` URL to hand to a browser's download
 * attribute or an Android intent, not a filesystem path.
 */
@Serializable
data class AndroidAppMetadataResponse(
    val versionName: String,
    val versionCode: Int,
    val sizeBytes: Long,
    val buildTime: String,
    val sha256: String,
    val downloadPath: String,
)
