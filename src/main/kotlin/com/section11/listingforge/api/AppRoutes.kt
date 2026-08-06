package com.section11.listingforge.api

import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.dto.AndroidAppMetadataResponse
import com.section11.listingforge.error.ResourceNotFoundException
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import java.io.File

private val json = Json { ignoreUnknownKeys = true }

internal const val ANDROID_METADATA_FILE_NAME = "app-metadata.json"
internal const val ANDROID_APK_FILE_NAME = "listingforge.apk"

/**
 * Unauthenticated app-info endpoints for the client's About screen, which
 * must render before sign-in - unlike [apiRoutes], nothing here resolves a
 * userId.
 *
 * GET /api/app/android reads [ANDROID_METADATA_FILE_NAME] out of
 * [AppConfig.downloads]' directory rather than computing it at request time:
 * `deploy/deploy.sh` writes that sidecar on the workstation (where
 * `sha256sum`/`stat` exist) alongside the APK, because sha256-ing a ~30MB
 * file per request on a Raspberry Pi is wasteful and the Pi has no `aapt` to
 * parse an APK for its version. 404s when [AppConfig.downloads] is unset, or
 * either the sidecar or the APK itself is missing - a stale sidecar pointing
 * at a deleted APK must not tell the client a download exists.
 */
fun Route.appRoutes(config: AppConfig) {
    get("/api/app/android") {
        val dir = config.downloads.dir
            ?: throw ResourceNotFoundException("No Android build published")
        val metadataFile = File(dir, ANDROID_METADATA_FILE_NAME)
        val apkFile = File(dir, ANDROID_APK_FILE_NAME)
        if (!metadataFile.exists() || !apkFile.exists()) {
            throw ResourceNotFoundException("No Android build published")
        }
        val metadata = json.decodeFromString<AndroidAppMetadataResponse>(metadataFile.readText())
        call.respond(metadata)
    }
}
