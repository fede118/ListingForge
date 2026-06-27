package com.section11.listingforge.auth

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions

private const val BEARER_PREFIX = "Bearer "

/**
 * Resolves the signed-in user from either transport, in priority order:
 *
 *   1. `Authorization: Bearer <token>`  - native clients (Android)
 *   2. `SESSION` cookie                 - browser clients (web SPA)
 *
 * This is the single place that knows there are two credential carriers;
 * everything downstream just receives a userId. Returns null when neither a
 * valid bearer token nor a session cookie is present.
 */
fun ApplicationCall.resolveUserId(tokens: SessionTokenService): String? {
    val authHeader = request.header(HttpHeaders.Authorization)
    if (authHeader != null && authHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) {
        val token = authHeader.substring(BEARER_PREFIX.length).trim()
        tokens.verify(token)?.let { return it }
    }
    return sessions.get<UserSession>()?.userId
}
