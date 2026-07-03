package com.section11.listingforge.auth

import io.ktor.server.application.ApplicationCall

/**
 * Decides who a request belongs to. A fun interface (not just RealUserResolver
 * inline) so route tests can supply a trivial lambda instead of standing up real
 * session/bearer plumbing - see ApiRoutesTest.
 *
 * There is only one production implementation: RealUserResolver, which enforces
 * a session cookie or bearer token (see resolveUserId). MOCK mode uses it too -
 * MOCK fakes the Etsy upstream and the OAuth token exchange, but a session still
 * has to exist before any /api route will resolve a user. (A MockUserResolver that
 * treated every caller as already signed in used to stand in here; it's gone now
 * that MOCK runs a real, if fake, sign-in flow - see FakeOAuthClient/FakeConsentScreen.)
 */
fun interface UserResolver {
    fun resolve(call: ApplicationCall): String?
}

/** The only UserResolver: a real credential (cookie or bearer) must be present. */
class RealUserResolver(private val tokens: SessionTokenService) : UserResolver {
    override fun resolve(call: ApplicationCall): String? = call.resolveUserId(tokens)
}
