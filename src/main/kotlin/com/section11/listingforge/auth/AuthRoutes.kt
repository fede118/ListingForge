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
    sessionTokens: SessionTokenService,
) {
    get("/auth/login") {
        // The client tells us how it wants control handed back after consent:
        // a browser gets a cookie + redirect to the SPA; the app gets a bearer
        // token on a deep link. Default to WEB for backward compatibility.
        val client = when (call.request.queryParameters["client"]?.lowercase()) {
            "android" -> AuthClient.ANDROID
            else -> AuthClient.WEB
        }
        val verifier = Pkce.newVerifier()
        val challenge = Pkce.challengeFor(verifier)
        val state = Pkce.newState()
        pendingAuth.put(state, verifier, client)

        val authorizeUrl = URLBuilder(config.etsy.authorizeUrl).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", config.etsy.keystring)
            parameters.append("redirect_uri", config.etsy.redirectUri)
            parameters.append("scope", config.etsy.oauthScopes)
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

        when (pending.client) {
            AuthClient.WEB -> {
                // Browser: plant the HttpOnly session cookie (JS can't read it,
                // so XSS can't steal it) and bounce back to the SPA, which then
                // fetches the signed-in user. No credential ever reaches JS.
                call.sessions.set(UserSession(record.userId))
                call.respondRedirect(config.client.frontendOrigin)
            }
            AuthClient.ANDROID -> {
                // Native app: cookies don't fit. Mint a signed bearer token and
                // hand it back on the registered deep link's fragment. The app
                // stores it in secure storage and sends it as Authorization.
                val token = sessionTokens.issue(record.userId)
                call.respondRedirect("${config.client.androidAuthDeepLink}#token=$token")
            }
        }
    }

    post("/auth/logout") {
        // Works for both transports: resolve whoever is calling, drop their Etsy
        // tokens. A bearer token stays signature-valid until it expires, but with
        // no stored Etsy token behind it, every API call 401s - effectively
        // logged out. Clearing the cookie ends the web session immediately.
        val userId = call.resolveUserId(sessionTokens)
        if (userId != null) {
            tokenStore.delete(userId)
            call.sessions.clear<UserSession>()
        }
        call.respondText("Logged out")
    }
}
