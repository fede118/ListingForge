package com.section11.listingforge.api

import com.section11.listingforge.auth.UserResolver
import com.section11.listingforge.dto.TaxonomyResponse
import com.section11.listingforge.error.NotAuthenticatedException
import com.section11.listingforge.etsy.EtsyApi
import io.ktor.http.ContentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Proxied Etsy endpoints. Each client presents its own credential - a session
 * cookie (web) or an Authorization: Bearer token (Android) - which resolveUserId
 * collapses to a single userId. The server loads that user's Etsy token from
 * SQLite and calls Etsy; the token never crosses back to the client.
 */
fun Route.apiRoutes(etsy: EtsyApi, userResolver: UserResolver) {
    get("/api/me") {
        val userId = userResolver.resolve(call)
            ?: throw NotAuthenticatedException("Not signed in")
        val body = etsy.getMe(userId)
        call.respondText(body, ContentType.Application.Json)
    }

    get("/api/shop") {
        val userId = userResolver.resolve(call)
            ?: throw NotAuthenticatedException("Not signed in")
        call.respond(etsy.getShop(userId))
    }

    get("/api/taxonomy") {
        userResolver.resolve(call)
            ?: throw NotAuthenticatedException("Not signed in")
        call.respond(TaxonomyResponse(etsy.getTaxonomy()))
    }
}
