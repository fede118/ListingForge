package com.section11.listingforge.etsy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The slices of Etsy's responses the BFF actually reads. Internal on purpose:
 * they're parse-time scaffolding for mapping upstream JSON into our own DTOs,
 * not part of any client-facing contract. ignoreUnknownKeys (set on the JSON
 * codec) lets these stay this small as Etsy's payloads grow.
 */

/** From GET /users/me: just the shop_id we pivot on. Null when the user has no shop. */
@Serializable
internal data class EtsyUser(
    @SerialName("shop_id") val shopId: Long? = null,
)

/** From GET /shops/{shop_id}: the id + name we surface as ShopResponse. */
@Serializable
internal data class EtsyShop(
    @SerialName("shop_id") val shopId: Long,
    @SerialName("shop_name") val shopName: String,
)

/**
 * From GET /seller-taxonomy/nodes: a category node, nested via `children`.
 * Etsy's payload carries more fields (level, parent_id, full path ids); only
 * the ones needed to flatten the tree are modeled here.
 */
@Serializable
internal data class EtsyTaxonomyNode(
    val id: Long,
    val name: String,
    val children: List<EtsyTaxonomyNode> = emptyList(),
)

/** The top-level envelope GET /seller-taxonomy/nodes returns. */
@Serializable
internal data class EtsyTaxonomyResponse(
    val results: List<EtsyTaxonomyNode>,
)

/** From POST /shops/{shop_id}/listings: just the id and state Task 9 surfaces. */
@Serializable
internal data class EtsyListing(
    @SerialName("listing_id") val listingId: Long,
    val state: String,
)

/** From POST .../listings/{listing_id}/images: the id and rank Task 9 surfaces. */
@Serializable
internal data class EtsyListingImage(
    @SerialName("listing_image_id") val listingImageId: Long,
    val rank: Int,
)

/** From POST .../listings/{listing_id}/files: just the id Task 9 surfaces. */
@Serializable
internal data class EtsyListingFile(
    @SerialName("listing_file_id") val listingFileId: Long,
)

/**
 * Etsy's error envelope on a non-2xx response. Both fields are optional and
 * Etsy is inconsistent about which it populates, so EtsyApiClient falls back
 * across both before giving up on a readable message.
 */
@Serializable
internal data class EtsyErrorResponse(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

/**
 * The Etsy listing-editor page for a draft, keyed by listing id. Shared by
 * EtsyApiClient and FakeEtsyApi so both build `editUrl` identically.
 */
internal const val ETSY_LISTING_EDITOR_BASE = "https://www.etsy.com/your/shops/me/listing-editor/edit"

/** Fixed on every createDraftListing call - this BFF only ever creates digital listings. */
internal const val ETSY_LISTING_TYPE_DOWNLOAD = "download"
