package com.section11.listingforge.error

/**
 * Domain exceptions that StatusPages maps to specific HTTP statuses, so handlers
 * can fail by throwing rather than threading status codes through return values.
 */

/** A request that requires a signed-in user has none (or no stored token). -> 401 */
class NotAuthenticatedException(message: String) : RuntimeException(message)
