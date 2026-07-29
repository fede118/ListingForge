package com.section11.listingforge.plugins

import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.config.AppMode
import com.section11.listingforge.config.ClientConfig
import com.section11.listingforge.config.DbConfig
import com.section11.listingforge.config.EtsyConfig
import com.section11.listingforge.config.ServerConfig
import com.section11.listingforge.config.SessionConfig
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the CORS method allowlist, which no other test reaches: route tests
 * call the endpoints directly, and only a browser sends the preflight OPTIONS
 * that this configuration answers. A method missing from the allowlist fails
 * the preflight with 403 and the route never runs — that is how PUT and DELETE
 * on /api/templates were unreachable from the web client while working on
 * Android, which sends no preflight.
 */
class CorsTest {

    private companion object {
        const val ORIGIN = "http://localhost:3000"
    }

    private val config = AppConfig(
        appMode = AppMode.MOCK,
        server = ServerConfig(port = 8080),
        db = DbConfig(path = "unused-in-this-test.db"),
        client = ClientConfig(frontendOrigin = ORIGIN, androidAuthDeepLink = "listingforge://auth"),
        session = SessionConfig.mock(),
        etsy = EtsyConfig.mock(),
    )

    /** The preflight a browser sends before a cross-origin [method] request. */
    private suspend fun io.ktor.client.HttpClient.preflight(method: HttpMethod): HttpResponse =
        options("/api/templates/1") {
            header(HttpHeaders.Origin, ORIGIN)
            header(HttpHeaders.AccessControlRequestMethod, method.value)
        }

    @Test
    fun preflight_allowsEveryMethodTheApiExposes() = testApplication {
        application { configureCors(config) }

        listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete).forEach { method ->
            val response = client.preflight(method)
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "preflight for ${method.value} was rejected — add it to configureCors's allowMethod list",
            )
            assertEquals(ORIGIN, response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
    }

    @Test
    fun preflight_rejectsAnUnknownOrigin() = testApplication {
        application { configureCors(config) }

        val response = client.options("/api/templates/1") {
            header(HttpHeaders.Origin, "http://evil.example")
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Put.value)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
