package com.section11.listingforge.dto

import kotlinx.serialization.Serializable

/** Uniform JSON error shape returned by the StatusPages handler. */
@Serializable
data class ErrorResponse(val error: String)
