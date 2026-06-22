package com.section11.listingforge.token

import java.time.Instant

/**
 * One Etsy user's tokens. `userId` is the numeric Etsy user id, which is the
 * prefix of the access token (everything before the first '.').
 */
data class TokenRecord(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
)

/**
 * Persistence boundary for tokens. Everything else depends on THIS interface,
 * never on SQLite directly, so the storage engine can change (Postgres, etc.)
 * without touching a single call site. This is the dependency-inversion seam
 * the whole "swap implementations" property hangs on.
 */
interface TokenStore {
    fun save(record: TokenRecord)
    fun get(userId: String): TokenRecord?
    fun delete(userId: String)
}
