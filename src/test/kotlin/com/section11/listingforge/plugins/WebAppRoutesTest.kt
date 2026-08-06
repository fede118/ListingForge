package com.section11.listingforge.plugins

import com.section11.listingforge.api.apiRoutes
import com.section11.listingforge.auth.UserResolver
import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.config.AppMode
import com.section11.listingforge.config.ClientConfig
import com.section11.listingforge.config.DbConfig
import com.section11.listingforge.config.EtsyConfig
import com.section11.listingforge.config.ServerConfig
import com.section11.listingforge.config.SessionConfig
import com.section11.listingforge.config.WebAppConfig
import com.section11.listingforge.etsy.FakeEtsyApi
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Proves the three things that decide whether serving the web app actually
 * works (Task 13): the static catch-all is registered *after* health/auth/api
 * so it can't shadow them (regardless of what Ktor's routing precedence would
 * do anyway - trusted here only because it's tested, not assumed), `.wasm`
 * comes back as `application/wasm`, and caching is "always revalidate" rather
 * than blind-cache or no-store.
 *
 * Mirrors Application.module()'s registration order (routes, then
 * configureWebApp last) with a minimal stand-in for health/auth/api instead of
 * the full DI graph - same shape as ApiRoutesTest/TemplateRoutesTest.
 */
class WebAppRoutesTest {

    private lateinit var bundleDir: File

    @BeforeTest
    fun setUp() {
        bundleDir = Files.createTempDirectory("webapp-bundle").toFile()
        bundleDir.resolve("index.html").writeText("<html>fake index</html>")
        bundleDir.resolve("skiko.wasm").writeBytes(byteArrayOf(0, 1, 2, 3))
    }

    @AfterTest
    fun tearDown() {
        bundleDir.deleteRecursively()
    }

    private fun configWithWebAppDir(dir: String?) = AppConfig(
        appMode = AppMode.MOCK,
        server = ServerConfig(port = 8080),
        db = DbConfig(path = "unused-in-this-test.db"),
        client = ClientConfig(frontendOrigin = "http://localhost:3000", androidAuthDeepLink = "listingforge://auth"),
        session = SessionConfig.mock(),
        etsy = EtsyConfig.mock(),
        webApp = WebAppConfig(dir = dir),
    )

    private fun Application.testModule(config: AppConfig) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        routing {
            get("/health") { call.respondText("ok") }
            get("/auth/callback") { call.respondText("callback handled") }
            apiRoutes(FakeEtsyApi(), UserResolver { "mock-user" })
        }
        configureWebApp(config)
    }

    @Test
    fun `serves index html at the root`() = testApplication {
        application { testModule(configWithWebAppDir(bundleDir.absolutePath)) }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("<html>fake index</html>", response.bodyAsText())
    }

    @Test
    fun `wasm files are served with the application wasm content type`() = testApplication {
        application { testModule(configWithWebAppDir(bundleDir.absolutePath)) }

        val response = client.get("/skiko.wasm")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application/wasm", response.headers[HttpHeaders.ContentType])
    }

    @Test
    fun `health stays reachable once the static catch-all is registered`() = testApplication {
        application { testModule(configWithWebAppDir(bundleDir.absolutePath)) }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun `the auth callback route stays reachable once the static catch-all is registered`() = testApplication {
        application { testModule(configWithWebAppDir(bundleDir.absolutePath)) }

        val response = client.get("/auth/callback")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("callback handled", response.bodyAsText())
    }

    @Test
    fun `a mapped api route still works once the static catch-all is registered`() = testApplication {
        application { testModule(configWithWebAppDir(bundleDir.absolutePath)) }

        val response = client.get("/api/shop")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `an unmapped api path 404s instead of falling through to index html`() = testApplication {
        application { testModule(configWithWebAppDir(bundleDir.absolutePath)) }

        val response = client.get("/api/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertNotEquals("<html>fake index</html>", response.bodyAsText())
    }

    @Test
    fun `an unchanged file 304s on revalidation instead of being re-sent`() = testApplication {
        application { testModule(configWithWebAppDir(bundleDir.absolutePath)) }

        val first = client.get("/skiko.wasm")
        val lastModified = assertNotNull(
            first.headers[HttpHeaders.LastModified],
            "expected a Last-Modified header from ConditionalHeaders",
        )
        assertEquals("no-cache", first.headers[HttpHeaders.CacheControl])

        val revalidated = client.get("/skiko.wasm") {
            header(HttpHeaders.IfModifiedSince, lastModified)
        }

        assertEquals(HttpStatusCode.NotModified, revalidated.status)
    }

    @Test
    fun `no static route is registered when WEBAPP_DIR is unset`() = testApplication {
        application { testModule(configWithWebAppDir(dir = null)) }

        val response = client.get("/")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
