package com.section11.listingforge.plugins

import com.section11.listingforge.config.AppConfig
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.header
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("Downloads")

/**
 * Serves the Android debug APK (Task 15) at /downloads. Mirrors
 * [configureWebApp]'s shape: a no-op - nothing installed, no route
 * registered - when [AppConfig.downloads]' `dir` is unset, so an API-only dev
 * run and the test suite need no build on disk. Must be called (like
 * [configureWebApp]) after health/auth/api/app routes are registered - see
 * WebAppRoutesTest, extended to cover this route too.
 *
 * No Content-Type override: Ktor 3.1's built-in file-extension MIME table
 * already maps `apk` to `application/vnd.android.package-archive`, the same
 * table Task 13 verified gets `.wasm` right. Content-Disposition does need
 * setting explicitly - `staticFiles` defaults to no disposition header at
 * all (the browser decides, and for an APK that's usually "try to open it"
 * rather than "download it") - so every `.apk` under this route is forced to
 * `attachment`.
 */
fun Application.configureDownloads(config: AppConfig) {
    val dir = config.downloads.dir
    if (dir == null) {
        log.info("DOWNLOADS_DIR not set - not serving Android downloads.")
        return
    }
    log.info("Serving Android downloads from {}", dir)
    routing {
        staticFiles("/downloads", File(dir)) {
            modify { file, call ->
                if (file.extension == "apk") {
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment
                            .withParameter(ContentDisposition.Parameters.FileName, file.name)
                            .toString(),
                    )
                }
            }
        }
    }
}
