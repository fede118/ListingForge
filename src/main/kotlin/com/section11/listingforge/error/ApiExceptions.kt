package com.section11.listingforge.error

/**
 * Domain exceptions that StatusPages maps to specific HTTP statuses, so handlers
 * can fail by throwing rather than threading status codes through return values.
 */

/** A request that requires a signed-in user has none (or no stored token). -> 401 */
class NotAuthenticatedException(message: String) : RuntimeException(message)

/** A request body fails input validation (e.g. a required field is blank). -> 400 */
class InvalidRequestException(message: String) : RuntimeException(message)

/** The referenced resource doesn't exist, or isn't visible to the caller. -> 404 */
class ResourceNotFoundException(message: String) : RuntimeException(message)

/**
 * Etsy answered a proxied call with a status this BFF doesn't have a specific
 * mapping for (i.e. not the 400/404 cases each Etsy call already handles) -
 * most commonly a 403 from a token that's missing a required OAuth scope.
 * Carries Etsy's own status + message so the client's failed-step UI can show
 * why, instead of surfacing as an opaque 500. -> 502
 */
class EtsyUpstreamException(val etsyStatus: Int, message: String) : RuntimeException(message)
