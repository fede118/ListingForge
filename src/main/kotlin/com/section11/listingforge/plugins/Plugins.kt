package com.section11.listingforge.plugins

import com.section11.listingforge.auth.UserSession
import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.dto.ErrorResponse
import com.section11.listingforge.error.EtsyUpstreamException
import com.section11.listingforge.error.InvalidRequestException
import com.section11.listingforge.error.NotAuthenticatedException
import com.section11.listingforge.error.ResourceNotFoundException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; prettyPrint = true })
    }
}

fun Application.configureSessions(config: AppConfig) {
    install(Sessions) {
        cookie<UserSession>("SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true                 // JavaScript cannot read it
            cookie.secure = config.session.cookieSecure // true once behind HTTPS
            cookie.extensions["SameSite"] = "Lax"   // sent on the top-level callback redirect
            // HMAC-sign the cookie so the userId it carries can't be forged.
            transform(
                SessionTransportTransformerMessageAuthentication(
                    config.session.signKey.toByteArray()
                )
            )
        }
    }
}

/**
 * Cross-origin access for the web client, which runs on its own origin (the
 * frontend origin in [AppConfig]) rather than being served by this BFF.
 *
 * Every method the API exposes must be allowlisted here, not just the ones
 * Ktor's CORS plugin permits by default (GET/POST/HEAD): the browser sends a
 * preflight OPTIONS for PUT and DELETE, and a method missing from this list is
 * rejected at the preflight with 403 — before the route runs, so the failure
 * shows up as a CORS error with no trace of the real request. Android issues no
 * preflight, so a gap here is invisible on that platform.
 */
fun Application.configureCors(config: AppConfig) {
    val host = config.client.frontendOrigin
        .removePrefix("https://")
        .removePrefix("http://")
    install(CORS) {
        allowHost(host, schemes = listOf("http", "https"))
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)      // bearer transport (also lets a web client opt into it)
        allowCredentials = true                     // required so cookies can cross origin
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
    }
}

fun Application.configureCallLogging() {
    install(CallLogging) {
        level = Level.INFO
    }
}

fun Application.configureStatusPages() {
    val log = LoggerFactory.getLogger("StatusPages")
    install(StatusPages) {
        // Expected, client-caused failures map to their own status with a safe message.
        exception<NotAuthenticatedException> { call, cause ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse(cause.message ?: "Not authenticated")
            )
        }
        exception<InvalidRequestException> { call, cause ->
            log.warn("400 on ${call.request.httpMethod.value} ${call.request.path()}: ${cause.message}")
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: "Invalid request")
            )
        }
        exception<ResourceNotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(cause.message ?: "Not found")
            )
        }
        // Etsy answered with a status this BFF has no specific mapping for (e.g.
        // 403 from a token missing a required scope). Not our fault, not the
        // caller's - a bad gateway, with Etsy's own message passed through so
        // the client's failed-step UI can show why.
        exception<EtsyUpstreamException> { call, cause ->
            log.warn("Etsy returned status ${cause.etsyStatus}: ${cause.message}")
            call.respond(
                HttpStatusCode.BadGateway,
                ErrorResponse(cause.message ?: "Etsy rejected the request")
            )
        }
        // Anything else is a server fault: log the detail, return a generic message
        // so internals never leak to the client.
        exception<Throwable> { call, cause ->
            log.error("Unhandled error", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Internal error")
            )
        }
    }
}
