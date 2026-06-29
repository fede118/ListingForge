package com.section11.listingforge.auth

import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.token.TokenRecord
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class EtsyTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long,
    // Etsy returns a fresh refresh_token on exchange; on refresh it may omit it,
    // so it's nullable and we fall back to the one we already hold.
    @SerialName("refresh_token") val refreshToken: String? = null,
)

/**
 * Talks to Etsy's OAuth token endpoint. PKCE means NO client_secret is sent
 * here â€” the code_verifier is the proof. The numeric Etsy user id is the prefix
 * of the access token (before the first '.'); we adopt it as our primary key.
 */
class EtsyOAuthClient(
    private val http: HttpClient,
    private val config: AppConfig,
) {
    private val tokenUrl = config.etsy.tokenUrl

    suspend fun exchangeCode(code: String, verifier: String): TokenRecord {
        val response: EtsyTokenResponse = http.submitForm(
            url = tokenUrl,
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("client_id", config.etsy.keystring)
                append("redirect_uri", config.etsy.redirectUri)
                append("code", code)
                append("code_verifier", verifier)
            }
        ).body()
        return response.toRecord(fallbackRefresh = null)
    }

    suspend fun refresh(refreshToken: String): TokenRecord {
        val response: EtsyTokenResponse = http.submitForm(
            url = tokenUrl,
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("client_id", config.etsy.keystring)
                append("refresh_token", refreshToken)
            }
        ).body()
        return response.toRecord(fallbackRefresh = refreshToken)
    }

    private fun EtsyTokenResponse.toRecord(fallbackRefresh: String?): TokenRecord {
        val userId = accessToken.substringBefore(".")
        // Subtract a small skew so we never hand out a token that expires
        // mid-request.
        val expiresAt = Instant.now().plusSeconds(expiresIn - 60)
        val refresh = refreshToken ?: fallbackRefresh
            ?: error("Token response had no refresh_token and none was held")
        return TokenRecord(userId, accessToken, refresh, expiresAt)
    }
}
