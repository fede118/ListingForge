package com.section11.listingforge.plugins

import com.section11.listingforge.config.AppConfig
import io.ktor.http.CacheControl
import io.ktor.http.content.CachingOptions
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("WebApp")

/**
 * Serves the wasmJS bundle (Task 13) same-origin with the API: session cookies
 * are `SameSite=Lax`, which browsers withhold cross-origin, so serving app +
 * API from one origin is what makes the web client first-party and retires
 * CORS for it entirely. A no-op - nothing installed, no route registered -
 * when [AppConfig.webApp]'s `dir` is unset, so an API-only dev run (webpack
 * dev server serving the app instead) and the test suite need no bundle on
 * disk. Must be called after the API/auth/health routes are registered: this
 * installs a catch-all at "/" that would otherwise shadow `/health`, the
 * `/auth` routes (the OAuth callback especially) and unmapped `/api` paths.
 * Registration order alone isn't trusted to enforce that - see WebAppRoutesTest.
 *
 * The bundle's filenames are only partly content-hashed (the `.wasm` binaries
 * are; `webApp.js`, `styles.css` and `index.html` keep the same name every
 * build), so the static subtree is served always-revalidate: `ConditionalHeaders`
 * answers `If-Modified-Since` with a 304 for an unchanged file, and
 * `CachingHeaders` sends `Cache-Control: no-cache` - "revalidate", not
 * `no-store`. That is what lets a redeploy be "copy the folder in, refresh",
 * with no BFF restart and no stale bundle. Both are route-scoped, so `/api`,
 * `/auth` and `/health` are unaffected.
 */
fun Application.configureWebApp(config: AppConfig) {
    val dir = config.webApp.dir
    if (dir == null) {
        log.info("WEBAPP_DIR not set - not serving the web app (API-only).")
        return
    }
    log.info("Serving the web app from {}", dir)
    routing {
        route("/") {
            install(ConditionalHeaders)
            install(CachingHeaders) {
                options { _, _ -> CachingOptions(CacheControl.NoCache(visibility = null)) }
            }
            // No content-type override: Ktor 3.1's MIME table already maps `wasm` to
            // `application/wasm`, which `WebAssembly.instantiateStreaming` demands.
            staticFiles("/", File(dir))
        }
    }
}
