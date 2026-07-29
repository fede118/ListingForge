package com.section11.listingforge.api

import com.section11.listingforge.auth.UserResolver
import com.section11.listingforge.dto.ListingRequest
import com.section11.listingforge.error.InvalidRequestException
import com.section11.listingforge.error.NotAuthenticatedException
import com.section11.listingforge.error.ResourceNotFoundException
import com.section11.listingforge.etsy.EtsyApi
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.toByteArray

/**
 * The Task 9 submit pipeline (create a draft listing, then attach its images
 * and buyer file, one Etsy call per route) plus Task 12's browse-drafts read
 * (`GET /api/listings`). The BFF holds no state between the Task 9 calls (no
 * job/session record) - the client drives the sequence and retries whichever
 * step failed. Draft-only throughout: no route here can move a listing past
 * the draft state Etsy assigns on creation, and the browse route is read-only
 * - no editing, no publishing.
 */
fun Route.listingRoutes(etsy: EtsyApi, userResolver: UserResolver) {
    get("/api/listings") {
        val userId = call.requireUserId(userResolver)
        val state = call.listingState()
        val limit = call.limitParam()
        val offset = call.offsetParam()
        call.respond(etsy.getListings(userId, state, limit, offset))
    }

    post("/api/listings") {
        val userId = call.requireUserId(userResolver)
        val listing = call.receive<ListingRequest>()
        val created = etsy.createDraftListing(userId, listing)
        call.respond(HttpStatusCode.Created, created)
    }

    post("/api/listings/{listingId}/images") {
        val userId = call.requireUserId(userResolver)
        val listingId = call.listingId()
        val image = call.receiveImageUpload()
        val uploaded = etsy.uploadListingImage(userId, listingId, image.bytes, image.filename, image.rank)
        call.respond(HttpStatusCode.Created, uploaded)
    }

    post("/api/listings/{listingId}/file") {
        val userId = call.requireUserId(userResolver)
        val listingId = call.listingId()
        val file = call.receiveFileUpload()
        val uploaded = etsy.uploadListingFile(userId, listingId, file.bytes, file.filename)
        call.respond(HttpStatusCode.Created, uploaded)
    }
}

private suspend fun ApplicationCall.requireUserId(userResolver: UserResolver): String =
    userResolver.resolve(this) ?: throw NotAuthenticatedException("Not signed in")

private fun ApplicationCall.listingId(): Long =
    parameters["listingId"]?.toLongOrNull() ?: throw ResourceNotFoundException("No listing with that id")

/**
 * `draft` is the only state Task 12 needs, so it's also the only one accepted
 * - an unsupported value 400s rather than passing arbitrary input through to
 * Etsy, which would return states this BFF has never mapped or tested.
 */
private const val DEFAULT_LISTING_STATE = "draft"
private val SUPPORTED_LISTING_STATES = setOf(DEFAULT_LISTING_STATE)
private const val DEFAULT_LISTINGS_LIMIT = 25
private const val DEFAULT_LISTINGS_OFFSET = 0
private const val MAX_LISTINGS_LIMIT = 100

private fun ApplicationCall.listingState(): String {
    val state = request.queryParameters["state"] ?: DEFAULT_LISTING_STATE
    if (state !in SUPPORTED_LISTING_STATES) {
        throw InvalidRequestException(
            "Unsupported state '$state': only '$DEFAULT_LISTING_STATE' is supported"
        )
    }
    return state
}

/**
 * Etsy rejects a `limit` of 0 or above 100, so both are caught here instead of
 * being forwarded - otherwise a caller's paging mistake comes back as a 502
 * upstream failure rather than a 400 naming the parameter that was wrong.
 */
private fun ApplicationCall.limitParam(): Int {
    val raw = request.queryParameters["limit"] ?: return DEFAULT_LISTINGS_LIMIT
    return raw.toIntOrNull()?.takeIf { it in 1..MAX_LISTINGS_LIMIT }
        ?: throw InvalidRequestException("'limit' must be an integer between 1 and $MAX_LISTINGS_LIMIT")
}

private fun ApplicationCall.offsetParam(): Int {
    val raw = request.queryParameters["offset"] ?: return DEFAULT_LISTINGS_OFFSET
    return raw.toIntOrNull()?.takeIf { it >= 0 }
        ?: throw InvalidRequestException("'offset' must be a non-negative integer")
}

private data class ImageUpload(val bytes: ByteArray, val filename: String, val rank: Int)
private data class FileUpload(val bytes: ByteArray, val filename: String)

/** Multipart fields: `image` (file) + `rank` (1-based text). Both are required. */
private suspend fun ApplicationCall.receiveImageUpload(): ImageUpload {
    var bytes: ByteArray? = null
    var filename = "image"
    var rank: Int? = null

    receiveMultipart().forEachPart { part ->
        when {
            part is PartData.FileItem && part.name == "image" -> {
                bytes = part.provider().toByteArray()
                filename = part.originalFileName ?: filename
            }
            part is PartData.FormItem && part.name == "rank" -> rank = part.value.toIntOrNull()
        }
        part.dispose()
    }

    return ImageUpload(
        bytes = bytes ?: throw InvalidRequestException("Missing multipart field: image"),
        filename = filename,
        rank = rank ?: throw InvalidRequestException("Missing or invalid multipart field: rank"),
    )
}

/** Multipart fields: `file` (file) + `name` (buyer-facing filename text). Both are required. */
private suspend fun ApplicationCall.receiveFileUpload(): FileUpload {
    var bytes: ByteArray? = null
    var name: String? = null

    receiveMultipart().forEachPart { part ->
        when {
            part is PartData.FileItem && part.name == "file" -> bytes = part.provider().toByteArray()
            part is PartData.FormItem && part.name == "name" -> name = part.value
        }
        part.dispose()
    }

    return FileUpload(
        bytes = bytes ?: throw InvalidRequestException("Missing multipart field: file"),
        filename = name ?: throw InvalidRequestException("Missing multipart field: name"),
    )
}
