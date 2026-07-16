package com.section11.listingforge.template

import java.time.Instant

/**
 * The listing-detail field values a template persists. Mirrors the client's
 * ListingDetails model exactly. `whoMade`/`whenMade` are Etsy wire values
 * (e.g. "i_did") kept opaque here - the client owns Etsy-constraint
 * validation, this layer just stores what it's given.
 */
data class TemplateFields(
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
)

/** A stored template, scoped to the shop that created it. */
data class TemplateRecord(
    val id: Long,
    val shopId: Long,
    val fields: TemplateFields,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Persistence boundary for listing-details templates, scoped by `shopId` the
 * same way every other shop-scoped concern in this BFF is - single shop now,
 * modeled for many. Every method takes the caller's shopId and never returns
 * or mutates a row belonging to a different shop, so a template id from
 * another shop is indistinguishable from one that doesn't exist at all.
 */
interface TemplateStore {
    fun create(shopId: Long, fields: TemplateFields): TemplateRecord

    fun list(shopId: Long): List<TemplateRecord>

    /** Null if `id` doesn't exist, or exists under a different shop. */
    fun update(shopId: Long, id: Long, fields: TemplateFields): TemplateRecord?

    /** True if a row was actually deleted. */
    fun delete(shopId: Long, id: Long): Boolean
}
