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
import io.ktor.server.routing.post
import io.ktor.utils.io.toByteArray

/**
 * The Task 9 submit pipeline: create a draft listing, then attach its images
 * and buyer file, one Etsy call per route. The BFF holds no state between
 * these calls (no job/session record) - the client drives the sequence and
 * retries whichever step failed. Draft-only: no route here can move a
 * listing past the draft state Etsy assigns on creation.
 */
fun Route.listingRoutes(etsy: EtsyApi, userResolver: UserResolver) {
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
