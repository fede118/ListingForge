package com.section11.listingforge.plugins

import com.section11.listingforge.api.ANDROID_APK_FILE_NAME
import com.section11.listingforge.api.ANDROID_METADATA_FILE_NAME
import com.section11.listingforge.api.apiRoutes
import com.section11.listingforge.api.appRoutes
import com.section11.listingforge.auth.UserResolver
import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.config.AppMode
import com.section11.listingforge.config.ClientConfig
import com.section11.listingforge.config.DbConfig
import com.section11.listingforge.config.DownloadsConfig
import com.section11.listingforge.config.EtsyConfig
import com.section11.listingforge.config.ServerConfig
import com.section11.listingforge.config.SessionConfig
import com.section11.listingforge.config.WebAppConfig
import com.section11.listingforge.error.ResourceNotFoundException
import com.section11.listingforge.etsy.FakeEtsyApi
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
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
import kotlin.test.assertNull

/**
 * Proves the things that decide whether serving the web app and the Android
 * download (Task 15) actually work: the static catch-alls ("/downloads",
 * then "/") are registered *after* health/auth/api/app so they can't shadow
 * them (regardless of what Ktor's routing precedence would do anyway -
 * trusted here only because it's tested, not assumed), `.wasm` comes back as
 * `application/wasm`, `.apk` comes back as `application/vnd.android.package-archive`
 * with a forced `Content-Disposition: attachment`, and caching is "always
 * revalidate" rather than blind-cache or no-store.
 *
 * Mirrors Application.module()'s registration order (routes, then
 * configureDownloads, then configureWebApp last) with a minimal stand-in for
 * health/auth/api/app instead of the full DI graph - same shape as
 * ApiRoutesTest/TemplateRoutesTest.
 */
class WebAppRoutesTest {

    private lateinit var bundleDir: File
    private lateinit var downloadsDir: File

    @BeforeTest
    fun setUp() {
        bundleDir = Files.createTempDirectory("webapp-bundle").toFile()
        bundleDir.resolve("index.html").writeText("<html>fake index</html>")
        bundleDir.resolve("skiko.wasm").writeBytes(byteArrayOf(0, 1, 2, 3))

        downloadsDir = Files.createTempDirectory("downloads").toFile()
        downloadsDir.resolve(ANDROID_APK_FILE_NAME).writeBytes(byteArrayOf(0, 1, 2, 3))
        downloadsDir.resolve(ANDROID_METADATA_FILE_NAME).writeText(
            """{"versionName":"1.0","versionCode":47,"sizeBytes":4,"buildTime":"2026-08-06T12:34:56Z","sha256":"deadbeef","downloadPath":"/downloads/listingforge.apk"}""",
        )
    }

    @AfterTest
    fun tearDown() {
        bundleDir.deleteRecursively()
        downloadsDir.deleteRecursively()
    }

    private fun configWithDirs(webAppDir: String?, downloadsDir: String?) = AppConfig(
        appMode = AppMode.MOCK,
        server = ServerConfig(port = 8080),
        db = DbConfig(path = "unused-in-this-test.db"),
        client = ClientConfig(frontendOrigin = "http://localhost:3000", androidAuthDeepLink = "listingforge://auth"),
        session = SessionConfig.mock(),
        etsy = EtsyConfig.mock(),
        webApp = WebAppConfig(dir = webAppDir),
        downloads = DownloadsConfig(dir = downloadsDir),
    )

    private fun configWithWebAppDir(dir: String?) = configWithDirs(webAppDir = dir, downloadsDir = null)

    private fun Application.testModule(config: AppConfig) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(StatusPages) {
            exception<ResourceNotFoundException> { call, cause ->
                call.respond(HttpStatusCode.NotFound, mapOf("error" to cause.message))
            }
        }
        routing {
            get("/health") { call.respondText("ok") }
            get("/auth/callback") { call.respondText("callback handled") }
            apiRoutes(FakeEtsyApi(), UserResolver { "mock-user" })
            appRoutes(config)
        }
        configureDownloads(config)
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

    @Test
    fun `downloads serves the apk with the android package archive content type`() = testApplication {
        application {
            testModule(configWithDirs(webAppDir = bundleDir.absolutePath, downloadsDir = downloadsDir.absolutePath))
        }

        val response = client.get("/downloads/$ANDROID_APK_FILE_NAME")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application/vnd.android.package-archive", response.headers[HttpHeaders.ContentType])
    }

    @Test
    fun `downloads forces the apk to be a browser download rather than rendered inline`() = testApplication {
        application {
            testModule(configWithDirs(webAppDir = bundleDir.absolutePath, downloadsDir = downloadsDir.absolutePath))
        }

        val response = client.get("/downloads/$ANDROID_APK_FILE_NAME")

        assertEquals(
            "attachment; filename=$ANDROID_APK_FILE_NAME",
            response.headers[HttpHeaders.ContentDisposition],
        )
    }

    @Test
    fun `no downloads route is registered when DOWNLOADS_DIR is unset`() = testApplication {
        application { testModule(configWithDirs(webAppDir = bundleDir.absolutePath, downloadsDir = null)) }

        val response = client.get("/downloads/$ANDROID_APK_FILE_NAME")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `the downloads route stays reachable once the web app catch-all is registered`() = testApplication {
        application {
            testModule(configWithDirs(webAppDir = bundleDir.absolutePath, downloadsDir = downloadsDir.absolutePath))
        }

        val response = client.get("/downloads/$ANDROID_APK_FILE_NAME")

        assertEquals(HttpStatusCode.OK, response.status)
        assertNotEquals("<html>fake index</html>", response.bodyAsText())
    }

    @Test
    fun `GET api app android returns the metadata sidecar as json`() = testApplication {
        application { testModule(configWithDirs(webAppDir = null, downloadsDir = downloadsDir.absolutePath)) }

        val response = client.get("/api/app/android")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            """{"versionName":"1.0","versionCode":47,"sizeBytes":4,"buildTime":"2026-08-06T12:34:56Z","sha256":"deadbeef","downloadPath":"/downloads/listingforge.apk"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `GET api app android is 404 when DOWNLOADS_DIR is unset`() = testApplication {
        application { testModule(configWithDirs(webAppDir = null, downloadsDir = null)) }

        val response = client.get("/api/app/android")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET api app android is 404 when the metadata sidecar is missing`() = testApplication {
        downloadsDir.resolve(ANDROID_METADATA_FILE_NAME).delete()
        application { testModule(configWithDirs(webAppDir = null, downloadsDir = downloadsDir.absolutePath)) }

        val response = client.get("/api/app/android")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET api app android is 404 when the apk itself is missing`() = testApplication {
        downloadsDir.resolve(ANDROID_APK_FILE_NAME).delete()
        application { testModule(configWithDirs(webAppDir = null, downloadsDir = downloadsDir.absolutePath)) }

        val response = client.get("/api/app/android")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `api app android stays reachable once both static catch-alls are registered`() = testApplication {
        application {
            testModule(configWithDirs(webAppDir = bundleDir.absolutePath, downloadsDir = downloadsDir.absolutePath))
        }

        val response = client.get("/api/app/android")

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.headers[HttpHeaders.ContentDisposition])
    }
}
