package com.section11.listingforge.auth

import com.section11.listingforge.api.apiRoutes
import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.config.AppMode
import com.section11.listingforge.config.ClientConfig
import com.section11.listingforge.config.DbConfig
import com.section11.listingforge.config.EtsyConfig
import com.section11.listingforge.config.ServerConfig
import com.section11.listingforge.config.SessionConfig
import com.section11.listingforge.error.NotAuthenticatedException
import com.section11.listingforge.etsy.FakeEtsyApi
import com.section11.listingforge.token.TokenRecord
import com.section11.listingforge.token.TokenStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.setCookie
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the MOCK sign-in flow: /auth/login (stub consent) ->
 * /auth/callback (fake token exchange) -> session/bearer -> /api/me. Boots the
 * real authRoutes/apiRoutes wiring in-process via testApplication, with
 * FakeOAuthClient + FakeConsentScreen standing in for Etsy - exactly the
 * bindings AppModule chooses in APP_MODE=mock - plus a trivial in-memory
 * TokenStore so nothing touches SQLite.
 *
 * The key assertion these tests exist to make: /api/me is unauthenticated
 * until the flow completes. MOCK used to skip straight to "signed in"; now it
 * doesn't, and that's the behavior this file locks in.
 */
class AuthRoutesTest {

    private val mockConfig = AppConfig(
        appMode = AppMode.MOCK,
        server = ServerConfig(port = 8080),
        db = DbConfig(path = "unused-in-this-test"),
        client = ClientConfig(
            frontendOrigin = "http://localhost:5173",
            androidAuthDeepLink = "listingforge://auth",
        ),
        session = SessionConfig.mock(),
        etsy = EtsyConfig.mock(),
    )

    private class InMemoryTokenStore : TokenStore {
        private val records = mutableMapOf<String, TokenRecord>()
        override fun save(record: TokenRecord) { records[record.userId] = record }
        override fun get(userId: String): TokenRecord? = records[userId]
        override fun delete(userId: String) { records.remove(userId) }
    }

    private fun Application.testModule(pendingAuth: PendingAuthStore, tokenStore: TokenStore) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Sessions) {
            cookie<UserSession>("SESSION") {
                transform(
                    SessionTransportTransformerMessageAuthentication(
                        mockConfig.session.signKey.toByteArray()
                    )
                )
            }
        }
        install(StatusPages) {
            exception<NotAuthenticatedException> { call, cause ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to cause.message))
            }
        }
        val sessionTokens = SessionTokenService(
            mockConfig.session.signKey.toByteArray(),
            mockConfig.session.tokenTtlSeconds,
        )
        routing {
            authRoutes(mockConfig, pendingAuth, FakeOAuthClient(), tokenStore, sessionTokens, FakeConsentScreen())
            apiRoutes(FakeEtsyApi(), RealUserResolver(sessionTokens))
        }
    }

    /** Pulls the `state=...` value out of the stub consent page's approve link. */
    private fun stateFrom(consentHtml: String): String {
        val match = Regex("""state=([^"&\s]+)""").find(consentHtml)
        return assertNotNull(match, "consent page did not contain a state").groupValues[1]
    }

    @Test
    fun `api me is unauthenticated before sign-in`() = testApplication {
        application { testModule(InMemoryPendingAuthStore(), InMemoryTokenStore()) }

        val response = client.get("/api/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `web flow - login then callback sets a session that api me honors`() = testApplication {
        application { testModule(InMemoryPendingAuthStore(), InMemoryTokenStore()) }
        // The callback responds 302 to the frontend origin / a custom deep-link
        // scheme; neither is a real reachable endpoint in this test, so disable
        // auto-follow and inspect the redirect response directly.
        val client = createClient { followRedirects = false }

        val loginResponse = client.get("/auth/login")
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        assertTrue(loginResponse.bodyAsText().contains("Approve as mock user"))
        val state = stateFrom(loginResponse.bodyAsText())

        val callbackResponse = client.get("/auth/callback?code=mock-auth-code&state=$state")
        assertEquals(HttpStatusCode.Found, callbackResponse.status)
        val sessionCookie = assertNotNull(
            callbackResponse.setCookie().firstOrNull { it.name == "SESSION" },
            "callback did not set a SESSION cookie",
        )

        val meResponse = client.get("/api/me") {
            header(HttpHeaders.Cookie, "SESSION=${sessionCookie.value}")
        }
        assertEquals(HttpStatusCode.OK, meResponse.status)
    }

    @Test
    fun `android flow - login then callback issues a bearer that api me honors`() = testApplication {
        application { testModule(InMemoryPendingAuthStore(), InMemoryTokenStore()) }
        val client = createClient { followRedirects = false }

        val loginResponse = client.get("/auth/login?client=android")
        val state = stateFrom(loginResponse.bodyAsText())

        val callbackResponse = client.get("/auth/callback?code=mock-auth-code&state=$state")
        assertEquals(HttpStatusCode.Found, callbackResponse.status)
        val location = assertNotNull(callbackResponse.headers[HttpHeaders.Location])
        assertTrue(location.startsWith("listingforge://auth?token="))
        val token = location.substringAfter("token=")

        val meResponse = client.get("/api/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, meResponse.status)
    }

    @Test
    fun `postman flow - login then callback renders the bearer token as html`() = testApplication {
        application { testModule(InMemoryPendingAuthStore(), InMemoryTokenStore()) }
        val client = createClient { followRedirects = false }

        val loginResponse = client.get("/auth/login?client=postman")
        val state = stateFrom(loginResponse.bodyAsText())

        val callbackResponse = client.get("/auth/callback?code=mock-auth-code&state=$state")
        assertEquals(HttpStatusCode.OK, callbackResponse.status)
        val body = callbackResponse.bodyAsText()
        assertTrue(body.contains("<code"))

        val token = Regex("""id="token"[^>]*>([^<]+)</code>""").find(body)
            ?.groupValues?.get(1)
        assertNotNull(token, "response did not contain a token in the #token <code> element")

        val meResponse = client.get("/api/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, meResponse.status)
    }

    @Test
    fun `callback rejects a state it never issued`() = testApplication {
        application { testModule(InMemoryPendingAuthStore(), InMemoryTokenStore()) }

        val response = client.get("/auth/callback?code=mock-auth-code&state=never-issued")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `logout clears the session so a fresh request is unauthenticated`() = testApplication {
        application { testModule(InMemoryPendingAuthStore(), InMemoryTokenStore()) }
        val client = createClient { followRedirects = false }

        val state = stateFrom(client.get("/auth/login").bodyAsText())
        val sessionCookie = assertNotNull(
            client.get("/auth/callback?code=mock-auth-code&state=$state")
                .setCookie().firstOrNull { it.name == "SESSION" }
        )
        val cookieHeader = "SESSION=${sessionCookie.value}"

        val logoutResponse = client.post("/auth/logout") { header(HttpHeaders.Cookie, cookieHeader) }
        assertEquals(HttpStatusCode.OK, logoutResponse.status)

        // The client is expected to drop the cookie once told to clear it; a
        // fresh request with no cookie at all confirms there's no session left.
        val meResponse = client.get("/api/me")
        assertEquals(HttpStatusCode.Unauthorized, meResponse.status)
    }
}
