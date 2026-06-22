package com.section11.listingforge.config

/**
 * All runtime configuration, read once from the environment at startup.
 *
 * Nothing is hardcoded and no secret lives in source. Required values fail
 * fast: if one is missing the server refuses to start, rather than throwing
 * deep inside a request handler later. That "validate at the boundary, on
 * boot" habit is one of the cheapest reliability wins in a backend.
 */
data class AppConfig(
    val serverPort: Int,
    val etsyKeystring: String,
    val etsySharedSecret: String,
    val redirectUri: String,
    val oauthScopes: String,
    val sessionSignKey: String,
    val frontendOrigin: String,
    val cookieSecure: Boolean,
    val dbPath: String,
    // Etsy endpoints. Env-overridable (so tests can point at a stub host); the
    // defaults below are the real production URLs and the single source of truth
    // for them — no longer scattered as literals across the OAuth/API clients.
    val etsyAuthorizeUrl: String,
    val etsyTokenUrl: String,
    val etsyApiBase: String,
) {
    companion object {
        private const val DEFAULT_ETSY_AUTHORIZE_URL = "https://www.etsy.com/oauth/connect"
        private const val DEFAULT_ETSY_TOKEN_URL = "https://api.etsy.com/v3/public/oauth/token"
        private const val DEFAULT_ETSY_API_BASE = "https://api.etsy.com/v3/application"

        fun fromEnv(): AppConfig {
            fun required(name: String): String =
                System.getenv(name) ?: error("Missing required env var: $name")
            fun optional(name: String, default: String): String =
                System.getenv(name) ?: default

            return AppConfig(
                serverPort = optional("SERVER_PORT", "8080").toInt(),
                etsyKeystring = required("ETSY_KEYSTRING"),
                etsySharedSecret = required("ETSY_SHARED_SECRET"),
                redirectUri = required("ETSY_REDIRECT_URI"),
                oauthScopes = optional("OAUTH_SCOPES", "shops_r"),
                sessionSignKey = required("SESSION_SIGN_KEY"),
                frontendOrigin = optional("FRONTEND_ORIGIN", "http://localhost:5173"),
                cookieSecure = optional("COOKIE_SECURE", "false").toBoolean(),
                dbPath = optional("DB_PATH", "listingforge.db"),
                etsyAuthorizeUrl = optional("ETSY_AUTHORIZE_URL", DEFAULT_ETSY_AUTHORIZE_URL),
                etsyTokenUrl = optional("ETSY_TOKEN_URL", DEFAULT_ETSY_TOKEN_URL),
                etsyApiBase = optional("ETSY_API_BASE", DEFAULT_ETSY_API_BASE),
            )
        }
    }
}
