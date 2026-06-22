package com.section11.listingforge.auth

import kotlinx.serialization.Serializable

/**
 * What rides in the session cookie: only the Etsy user id.
 *
 * The cookie is signed (tamper-proof) and HttpOnly (invisible to JavaScript),
 * and it carries NO token. Tokens stay server-side in SQLite, looked up by this
 * id. The browser holds an identity, never a credential — which is the whole
 * reason a BFF exists.
 */
@Serializable
data class UserSession(val userId: String)
