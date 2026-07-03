package com.section11.listingforge.api

import com.section11.listingforge.auth.UserResolver
import com.section11.listingforge.error.NotAuthenticatedException
import com.section11.listingforge.etsy.FakeEtsyApi
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Route-level tests for the proxied Etsy endpoints. These boot the routing
 * in-process via testApplication with the fakes injected directly â€” no server,
 * no port, no APP_MODE env. They cover what the FakeEtsyApi unit test can't:
 * the HTTP status, content type, and JSON shape the client actually receives,
 * plus the unauthenticated path.
 */
class ApiRoutesTest {

    /** Installs the same serialization + error mapping the real app uses, then the routes. */
    private fun Application.testModule(userResolver: UserResolver) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(StatusPages) {
            exception<NotAuthenticatedException> { call, cause ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to cause.message))
            }
        }
        routing { apiRoutes(FakeEtsyApi(), userResolver) }
    }

    @Test
    fun `GET api shop returns the shop id and name as json`() = testApplication {
        // A trivial always-signed-in resolver, standing in for whatever real
        // credential check ran upstream - this test is about the route's JSON
        // shape, not auth (see the 401 test below for that).
        application { testModule(UserResolver { "mock-user" }) }

        val response = client.get("/api/shop")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            """{"id":987654321,"name":"Demo Shop"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `GET api shop is 401 when the caller resolves to no user`() = testApplication {
        application { testModule(UserResolver { null }) }

        val response = client.get("/api/shop")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
