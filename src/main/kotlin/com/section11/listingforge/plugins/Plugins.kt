package com.section11.listingforge.plugins

import com.section11.listingforge.auth.UserSession
import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.dto.ErrorResponse
import com.section11.listingforge.error.NotAuthenticatedException
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

fun Application.configureCors(config: AppConfig) {
    // Forward-looking: only matters once a separate-origin web client calls in.
    // Same-origin browser testing doesn't trigger CORS at all.
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
