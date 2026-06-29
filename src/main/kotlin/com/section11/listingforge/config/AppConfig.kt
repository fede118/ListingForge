package com.section11.listingforge.config

/**
 * Which set of implementations the server runs with.
 *
 *  - PROD: the real thing. Talks to Etsy, enforces auth.
 *  - MOCK: a self-contained fake. Returns canned upstream responses and treats
 *    every caller as a fixed demo user, so the app can exercise the happy path
 *    with no OAuth dance and no real Etsy credentials. Deploy this as a separate
 *    host (e.g. mock.<domain>) and point the app at it to "swap environments".
 *
 * The mode is chosen once at boot; mock code never runs in a PROD process.
 */
enum class AppMode { PROD, MOCK }

/**
 * Reads raw values from the process environment. The one place that knows where
 * config comes from, so the config objects below stay plain data with simple
 * factories. `required` fails fast: a missing value stops the server at boot
 * rather than throwing deep inside a request later.
 */
internal object Env {
    fun required(name: String): String =
        System.getenv(name) ?: error("Missing required env var: $name")

    fun optional(name: String, default: String): String =
        System.getenv(name) ?: default
}

/** Where the server itself binds. */
data class ServerConfig(val port: Int) {
    companion object {
        fun fromEnv() = ServerConfig(port = Env.optional("SERVER_PORT", "8080").toInt())
    }
}

/** Where the SQLite file lives. */
data class DbConfig(val path: String) {
    companion object {
        fun fromEnv() = DbConfig(path = Env.optional("DB_PATH", "listingforge.db"))
    }
}

/**
 * Where each client type lives and gets sent after auth: the web SPA's origin
 * (also used for CORS) and the native app's deep link.
 */
data class ClientConfig(
    val frontendOrigin: String,
    val androidAuthDeepLink: String,
) {
    companion object {
        private const val DEFAULT_ANDROID_AUTH_DEEPLINK = "listingforge://auth"

        fun fromEnv() = ClientConfig(
            frontendOrigin = Env.optional("FRONTEND_ORIGIN", "http://localhost:5173"),
            androidAuthDeepLink = Env.optional("ANDROID_AUTH_DEEPLINK", DEFAULT_ANDROID_AUTH_DEEPLINK),
        )
    }
}

/**
 * Session/cookie settings. `signKey` HMAC-signs the cookie and bearer tokens, so
 * it's a secret and required in prod. Mock mode still installs the Sessions
 * plugin, so it needs *some* key, but never a real one.
 */
data class SessionConfig(
    val signKey: String,
    val tokenTtlSeconds: Long,
    val cookieSecure: Boolean,
) {
    companion object {
        private const val DEFAULT_TOKEN_TTL = "2592000" // 30 days

        private fun shared(signKey: String) = SessionConfig(
            signKey = signKey,
            tokenTtlSeconds = Env.optional("SESSION_TOKEN_TTL_SECONDS", DEFAULT_TOKEN_TTL).toLong(),
            cookieSecure = Env.optional("COOKIE_SECURE", "false").toBoolean(),
        )

        fun fromEnv() = shared(signKey = Env.required("SESSION_SIGN_KEY"))

        fun mock() = shared(signKey = "mock-session-sign-key-not-for-prod")
    }
}

/**
 * Everything needed to speak OAuth + API to Etsy. The credentials and redirect
 * are required in prod and fail fast if missing; the endpoint URLs default to
 * the real production hosts and are the single source of truth for them.
 *
 * `mock()` supplies throwaway placeholders. In mock mode none of these are ever
 * used (FakeEtsyApi/MockUserResolver replace the real clients), so this exists
 * only to satisfy the type — the smell of mode-specific values is confined here
 * instead of smeared across every field.
 */
data class EtsyConfig(
    val keystring: String,
    val sharedSecret: String,
    val redirectUri: String,
    val oauthScopes: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val apiBase: String,
) {
    companion object {
        private const val DEFAULT_AUTHORIZE_URL = "https://www.etsy.com/oauth/connect"
        private const val DEFAULT_TOKEN_URL = "https://api.etsy.com/v3/public/oauth/token"
        private const val DEFAULT_API_BASE = "https://api.etsy.com/v3/application"

        private fun shared(keystring: String, sharedSecret: String, redirectUri: String) = EtsyConfig(
            keystring = keystring,
            sharedSecret = sharedSecret,
            redirectUri = redirectUri,
            oauthScopes = Env.optional("OAUTH_SCOPES", "shops_r"),
            authorizeUrl = Env.optional("ETSY_AUTHORIZE_URL", DEFAULT_AUTHORIZE_URL),
            tokenUrl = Env.optional("ETSY_TOKEN_URL", DEFAULT_TOKEN_URL),
            apiBase = Env.optional("ETSY_API_BASE", DEFAULT_API_BASE),
        )

        fun fromEnv() = shared(
            keystring = Env.required("ETSY_KEYSTRING"),
            sharedSecret = Env.required("ETSY_SHARED_SECRET"),
            redirectUri = Env.required("ETSY_REDIRECT_URI"),
        )

        fun mock() = shared(
            keystring = "mock-keystring",
            sharedSecret = "mock-shared-secret",
            redirectUri = "http://localhost:8080/auth/callback",
        )
    }
}

/**
 * All runtime configuration, read once from the environment at startup and
 * grouped by concern so it stays cohesive as it grows.
 *
 * The mode-sensitive groups (etsy, session) are built through a `mock()` or
 * `fromEnv()` factory chosen once by mode here — rather than a per-field
 * "required unless mock" check. PROD demands real secrets and fails fast; MOCK
 * never touches them.
 */
data class AppConfig(
    val appMode: AppMode,
    val server: ServerConfig,
    val db: DbConfig,
    val client: ClientConfig,
    val session: SessionConfig,
    val etsy: EtsyConfig,
) {
    companion object {
        fun fromEnv(): AppConfig {
            val mode = when (Env.optional("APP_MODE", "prod").lowercase()) {
                "mock" -> AppMode.MOCK
                else -> AppMode.PROD
            }
            return AppConfig(
                appMode = mode,
                server = ServerConfig.fromEnv(),
                db = DbConfig.fromEnv(),
                client = ClientConfig.fromEnv(),
                session = if (mode == AppMode.MOCK) SessionConfig.mock() else SessionConfig.fromEnv(),
                etsy = if (mode == AppMode.MOCK) EtsyConfig.mock() else EtsyConfig.fromEnv(),
            )
        }
    }
}
