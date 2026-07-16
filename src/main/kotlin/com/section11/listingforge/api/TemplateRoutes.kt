package com.section11.listingforge.api

import com.section11.listingforge.auth.UserResolver
import com.section11.listingforge.dto.TemplateListResponse
import com.section11.listingforge.dto.TemplateRequest
import com.section11.listingforge.dto.TemplateResponse
import com.section11.listingforge.error.InvalidRequestException
import com.section11.listingforge.error.NotAuthenticatedException
import com.section11.listingforge.error.ResourceNotFoundException
import com.section11.listingforge.etsy.EtsyApi
import com.section11.listingforge.template.TemplateFields
import com.section11.listingforge.template.TemplateRecord
import com.section11.listingforge.template.TemplateStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * CRUD for saved listing-details templates. Templates never touch Etsy - the
 * only Etsy call here is resolving the caller's shop_id, the same way
 * GET /api/shop does, so a template is scoped to a shop exactly like every
 * other shop-scoped concern in this BFF.
 */
fun Route.templateRoutes(templates: TemplateStore, etsy: EtsyApi, userResolver: UserResolver) {
    route("/api/templates") {
        get {
            val shopId = call.resolveShopId(userResolver, etsy)
            call.respond(TemplateListResponse(templates.list(shopId).map { it.toResponse() }))
        }

        post {
            val shopId = call.resolveShopId(userResolver, etsy)
            val fields = call.receive<TemplateRequest>().toFieldsOrThrow()
            val created = templates.create(shopId, fields)
            call.respond(HttpStatusCode.Created, created.toResponse())
        }

        put("/{id}") {
            val shopId = call.resolveShopId(userResolver, etsy)
            val id = call.templateId()
            val fields = call.receive<TemplateRequest>().toFieldsOrThrow()
            val updated = templates.update(shopId, id, fields)
                ?: throw ResourceNotFoundException("No template with id $id")
            call.respond(updated.toResponse())
        }

        delete("/{id}") {
            val shopId = call.resolveShopId(userResolver, etsy)
            val id = call.templateId()
            if (!templates.delete(shopId, id)) {
                throw ResourceNotFoundException("No template with id $id")
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** Same resolution GET /api/shop uses: cookie/bearer -> userId -> Etsy's shop_id for that user. */
private suspend fun ApplicationCall.resolveShopId(userResolver: UserResolver, etsy: EtsyApi): Long {
    val userId = userResolver.resolve(this) ?: throw NotAuthenticatedException("Not signed in")
    return etsy.getShop(userId).id
}

private fun ApplicationCall.templateId(): Long =
    parameters["id"]?.toLongOrNull() ?: throw ResourceNotFoundException("No template with that id")

private fun TemplateRequest.toFieldsOrThrow(): TemplateFields {
    if (name.isBlank()) throw InvalidRequestException("name must not be blank")
    return TemplateFields(
        name = name,
        title = title,
        description = description,
        price = price,
        quantity = quantity,
        tags = tags,
        whoMade = whoMade,
        whenMade = whenMade,
        taxonomyId = taxonomyId,
        taxonomyPath = taxonomyPath,
        specsText = specsText,
    )
}

private fun TemplateRecord.toResponse() = TemplateResponse(
    id = id,
    name = fields.name,
    title = fields.title,
    description = fields.description,
    price = fields.price,
    quantity = fields.quantity,
    tags = fields.tags,
    whoMade = fields.whoMade,
    whenMade = fields.whenMade,
    taxonomyId = fields.taxonomyId,
    taxonomyPath = fields.taxonomyPath,
    specsText = fields.specsText,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)
