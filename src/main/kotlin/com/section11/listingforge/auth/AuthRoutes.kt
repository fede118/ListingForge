package com.section11.listingforge.auth

import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.token.TokenStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.protocolWithAuthority
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
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
 *                                 hand off to the consent screen (real Etsy in
 *                                 PROD, a stub page in MOCK - see ConsentScreen)
 *   GET  /auth/callback  finish -> validate state (CSRF), exchange code -> tokens,
 *                                 persist tokens, set the session cookie
 *   POST /auth/logout    -> drop the session and the stored tokens
 *
 * Dependencies are passed in (not service-located inside the handlers), which
 * keeps these routes trivially testable. oauth and consentScreen are bound by
 * mode at the composition root (AppModule); this function never knows which
 * mode it's running under.
 */
fun Route.authRoutes(
    config: AppConfig,
    pendingAuth: PendingAuthStore,
    oauth: OAuthClient,
    tokenStore: TokenStore,
    sessionTokens: SessionTokenService,
    consentScreen: ConsentScreen,
) {
    get("/auth/login") {
        // The client tells us how it wants control handed back after consent:
        // a browser gets a cookie + redirect to the SPA; the app gets a bearer
        // token on a deep link. Default to WEB for backward compatibility.
        val client = when (call.request.queryParameters["client"]?.lowercase()) {
            "android" -> AuthClient.ANDROID
            "postman" -> AuthClient.POSTMAN
            else -> AuthClient.WEB
        }
        val verifier = Pkce.newVerifier()
        val challenge = Pkce.challengeFor(verifier)
        val state = Pkce.newState()
        pendingAuth.put(state, verifier, client, resolveReturnOrigin(call, config))

        consentScreen.respond(call, state, challenge)
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
                call.respondRedirect(pending.returnOrigin)
            }
            AuthClient.ANDROID -> {
                // Native app: cookies don't fit. Mint a signed bearer token and
                // hand it back as a query parameter on the registered deep link,
                // so the app can read it with Uri.getQueryParameter("token"). The
                // app stores it in secure storage and sends it as Authorization.
                val token = sessionTokens.issue(record.userId)
                call.respondRedirect("${config.client.androidAuthDeepLink}?token=$token")
            }
            AuthClient.POSTMAN -> {
                val token = sessionTokens.issue(record.userId)
                call.respondText(postmanTokenHtml(token), ContentType.Text.Html)
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

/**
 * Which origin a WEB sign-in should land back on after consent, captured from
 * the request that *starts* the flow (`/auth/login`) rather than the callback:
 * Etsy (or, in MOCK, the stub consent page) sends the browser to
 * `ETSY_REDIRECT_URI` directly, which always points at this BFF - by the time
 * `/auth/callback` runs, the request that reached it carries no trace of
 * which origin the user actually started from. So this is read once here and
 * carried through `PendingAuthStore` instead.
 *
 * Prefers the `Origin` header, falling back to the origin portion of
 * `Referer`. The fallback is the path that actually runs: the web client
 * reaches `/auth/login` by a top-level GET navigation, and browsers omit
 * `Origin` on those (it's mainly a fetch/XHR/cross-site-POST header) while
 * still sending `Referer`. The dev server proxies `/auth/login` through
 * without rewriting headers, so this observes the dev origin there and the
 * BFF's own when the app is served same-origin.
 *
 * Restricted to an allowlist (`frontendOrigin` + the BFF's own
 * [AppConfig.server] origin) so a forged `Origin`/`Referer` can't turn this
 * into an open redirect; anything unrecognised or absent falls back to
 * `frontendOrigin`, matching pre-fix behavior.
 */
private fun resolveReturnOrigin(call: ApplicationCall, config: AppConfig): String {
    val candidate = call.request.header(HttpHeaders.Origin)
        ?: call.request.header(HttpHeaders.Referrer)?.let(::originOf)
    val allowlist = setOf(config.client.frontendOrigin, config.server.publicOrigin)
    return candidate?.takeIf { it in allowlist } ?: config.client.frontendOrigin
}

/** Extracts `scheme://host[:port]` from a full URL, e.g. a `Referer` header. */
private fun originOf(url: String): String? = runCatching { Url(url).protocolWithAuthority }.getOrNull()

/**
 * Renders the bearer token as selectable text for the person who just
 * completed consent to copy into Postman by hand. Same style of inline
 * HTML-in-Kotlin as FakeConsentScreen's stub page.
 */
private fun postmanTokenHtml(token: String) = """
    <!DOCTYPE html>
    <html>
      <head><title>ListingForge bearer token</title></head>
      <body style="font-family: sans-serif; max-width: 32rem; margin: 4rem auto;">
        <h1>Signed in</h1>
        <p>Paste this into the Postman collection's <b>token</b> variable:</p>
        <code id="token" style="display: block; padding: 1rem; background: #f0f0f0; word-break: break-all;">$token</code>
      </body>
    </html>
""".trimIndent()
