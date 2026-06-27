package com.section11.listingforge.api

import com.section11.listingforge.auth.SessionTokenService
import com.section11.listingforge.auth.resolveUserId
import com.section11.listingforge.error.NotAuthenticatedException
import com.section11.listingforge.etsy.EtsyApiClient
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Proxied Etsy endpoints. Each client presents its own credential - a session
 * cookie (web) or an Authorization: Bearer token (Android) - which resolveUserId
 * collapses to a single userId. The server loads that user's Etsy token from
 * SQLite and calls Etsy; the token never crosses back to the client.
 */
fun Route.apiRoutes(etsy: EtsyApiClient, sessionTokens: SessionTokenService) {
    get("/api/me") {
        val userId = call.resolveUserId(sessionTokens)
            ?: throw NotAuthenticatedException("Not signed in")
        val body = etsy.getMe(userId)
        call.respondText(body, ContentType.Application.Json)
    }
}
