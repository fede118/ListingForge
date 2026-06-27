package com.section11.listingforge.auth

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class PendingAuth(val verifier: String, val client: AuthClient, val createdAt: Instant)

/** Which frontend started the flow; decides how the callback hands control back. */
enum class AuthClient { WEB, ANDROID }

/**
 * Holds in-flight OAuth attempts: state -> the PKCE verifier issued for it.
 *
 * Deliberately in-memory and ephemeral. An auth attempt that doesn't finish
 * within minutes is worthless, and one that doesn't survive a restart is no
 * loss. Contrast with TokenStore (durable, SQLite): keeping ephemeral and
 * durable state in different places, with different lifetimes, is the point â€”
 * not an oversight.
 */
interface PendingAuthStore {
    fun put(state: String, verifier: String, client: AuthClient)
    /** Returns AND removes the entry (single-use); null if absent or expired. */
    fun consume(state: String): PendingAuth?
}

class InMemoryPendingAuthStore(
    private val ttlSeconds: Long = 600,
) : PendingAuthStore {
    private val entries = ConcurrentHashMap<String, PendingAuth>()

    override fun put(state: String, verifier: String, client: AuthClient) {
        entries[state] = PendingAuth(verifier, client, Instant.now())
    }

    override fun consume(state: String): PendingAuth? {
        val entry = entries.remove(state) ?: return null
        val age = Instant.now().epochSecond - entry.createdAt.epochSecond
        return if (age <= ttlSeconds) entry else null
    }
}
