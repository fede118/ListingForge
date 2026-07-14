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
