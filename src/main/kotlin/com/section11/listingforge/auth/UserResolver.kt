package com.section11.listingforge.auth

import io.ktor.server.application.ApplicationCall

/**
 * Decides who a request belongs to. Bound by mode at the composition root so the
 * proxied API routes don't know whether real auth is in force.
 *
 *  - RealUserResolver enforces a session cookie or bearer token (see resolveUserId).
 *  - MockUserResolver treats every caller as a fixed demo user, which is what
 *    lets mock mode return success with no OAuth dance.
 */
fun interface UserResolver {
    fun resolve(call: ApplicationCall): String?
}

/** Production: a real credential must be present. */
class RealUserResolver(private val tokens: SessionTokenService) : UserResolver {
    override fun resolve(call: ApplicationCall): String? = call.resolveUserId(tokens)
}

/** Mock: nobody needs to sign in; everyone is the same demo user. */
class MockUserResolver(private val demoUserId: String = "mock-user") : UserResolver {
    override fun resolve(call: ApplicationCall): String = demoUserId
}
