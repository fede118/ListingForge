package com.section11.listingforge.auth

import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.token.TokenStore
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set

/**
 * The login flow, three endpoints:
 *
 *   GET  /auth/login    start  -> mint verifier+challenge+state, stash verifier,
 *                                 redirect the browser to Etsy's consent screen
 *   GET  /auth/callback  finish -> validate state (CSRF), exchange code -> tokens,
 *                                 persist tokens, set the session cookie
 *   POST /auth/logout    -> drop the session and the stored tokens
 *
 * Dependencies are passed in (not service-located inside the handlers), which
 * keeps these routes trivially testable.
 */
fun Route.authRoutes(
    config: AppConfig,
    pendingAuth: PendingAuthStore,
    oauth: EtsyOAuthClient,
    tokenStore: TokenStore,
) {
    get("/auth/login") {
        val verifier = Pkce.newVerifier()
        val challenge = Pkce.challengeFor(verifier)
        val state = Pkce.newState()
        pendingAuth.put(state, verifier)

        val authorizeUrl = URLBuilder(config.etsyAuthorizeUrl).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", config.etsyKeystring)
            parameters.append("redirect_uri", config.redirectUri)
            parameters.append("scope", config.oauthScopes)
            parameters.append("state", state)
            parameters.append("code_challenge", challenge)
            parameters.append("code_challenge_method", "S256")
        }.buildString()

        call.respondRedirect(authorizeUrl)
    }

    get("/auth/callback") {
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]
        if (code == null || state == null) {
            call.respondText("Missing code or state", status = HttpStatusCode.BadRequest)
            return@get
        }

        // CSRF / replay defense: the returned state must map to a verifier WE
        // issued (and haven't already consumed). No match -> reject.
        val pending = pendingAuth.consume(state)
        if (pending == null) {
            call.respondText("Unknown or expired state", status = HttpStatusCode.BadRequest)
            return@get
        }

        val record = oauth.exchangeCode(code, pending.verifier)
        tokenStore.save(record)
        call.sessions.set(UserSession(record.userId))
        call.respondText("Signed in as Etsy user ${record.userId}")
    }

    post("/auth/logout") {
        val session = call.sessions.get<UserSession>()
        if (session != null) {
            tokenStore.delete(session.userId)
            call.sessions.clear<UserSession>()
        }
        call.respondText("Logged out")
    }
}
