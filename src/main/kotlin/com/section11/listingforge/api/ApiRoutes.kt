package com.section11.listingforge.api

import com.section11.listingforge.auth.UserSession
import com.section11.listingforge.etsy.EtsyApiClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions

/**
 * Proxied Etsy endpoints. The browser calls these with its session cookie; the
 * server resolves the cookie to a userId, loads that user's token from SQLite,
 * and calls Etsy. The token never crosses back to the client.
 */
fun Route.apiRoutes(etsy: EtsyApiClient) {
    get("/api/me") {
        val session = call.sessions.get<UserSession>()
        if (session == null) {
            call.respondText("Not signed in", status = HttpStatusCode.Unauthorized)
            return@get
        }
        val body = etsy.getMe(session.userId)
        call.respondText(body, ContentType.Application.Json)
    }
}
