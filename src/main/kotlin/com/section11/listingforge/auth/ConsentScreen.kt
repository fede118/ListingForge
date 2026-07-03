package com.section11.listingforge.auth

import com.section11.listingforge.config.AppConfig
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText

/**
 * Where GET /auth/login sends the human for the OAuth consent step, as an
 * interface so it can be swapped at the composition root like EtsyApi/OAuthClient.
 * PROD (EtsyConsentScreen) redirects to Etsy's real consent screen; MOCK
 * (FakeConsentScreen) renders a stub page that stands in for it.
 *
 * This is what makes MOCK mode an actual sign-in flow rather than a bypass: there
 * is still a distinct "go approve" step the caller must complete before a session
 * exists, even though no request ever reaches Etsy.
 */
interface ConsentScreen {
    suspend fun respond(call: ApplicationCall, state: String, challenge: String)
}

/** Production: bounce the browser to Etsy's real /oauth/connect consent screen. */
class EtsyConsentScreen(private val config: AppConfig) : ConsentScreen {
    override suspend fun respond(call: ApplicationCall, state: String, challenge: String) {
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
}

/**
 * Mock: a minimal, BFF-rendered HTML page standing in for Etsy's human consent
 * screen. Approving links to our OWN /auth/callback with a fake authorization
 * code - the real Etsy redirect never happens, so this works with no live Etsy
 * app and no network call.
 *
 * From here on the flow is unchanged: /auth/callback still validates `state`
 * against PendingAuthStore exactly as prod does, and still calls
 * `oauth.exchangeCode(...)` - it's just FakeOAuthClient underneath, returning a
 * canned token instead of hitting Etsy. That's the whole trick: only the human
 * consent screen and the token source are faked; state/session/cookie handling
 * is the real code path, so it's true coverage of the sign-in flow, not a bypass.
 */
class FakeConsentScreen : ConsentScreen {

    // The value is never inspected by FakeOAuthClient; it only needs to be
    // present so /auth/callback's "code == null" guard doesn't reject it.
    private val fakeCode = "mock-auth-code"

    override suspend fun respond(call: ApplicationCall, state: String, challenge: String) {
        call.respondText(consentHtml(state), ContentType.Text.Html)
    }

    private fun consentHtml(state: String) = """
        <!DOCTYPE html>
        <html>
          <head><title>Mock Etsy sign-in</title></head>
          <body style="font-family: sans-serif; max-width: 32rem; margin: 4rem auto;">
            <h1>Mock Etsy sign-in</h1>
            <p>
              This page stands in for Etsy's real consent screen. It only exists
              in MOCK mode - approving does not contact Etsy or any real service.
            </p>
            <a href="/auth/callback?code=$fakeCode&amp;state=$state">
              <button style="font-size: 1rem; padding: 0.5rem 1rem;">
                Approve as mock user
              </button>
            </a>
          </body>
        </html>
    """.trimIndent()
}
