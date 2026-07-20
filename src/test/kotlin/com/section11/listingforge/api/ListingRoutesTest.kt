package com.section11.listingforge.api

import com.section11.listingforge.auth.UserResolver
import com.section11.listingforge.dto.ListingFileResponse
import com.section11.listingforge.dto.ListingImageResponse
import com.section11.listingforge.dto.ListingRequest
import com.section11.listingforge.dto.ListingResponse
import com.section11.listingforge.dto.ShopResponse
import com.section11.listingforge.dto.TaxonomyNodeResponse
import com.section11.listingforge.error.InvalidRequestException
import com.section11.listingforge.error.NotAuthenticatedException
import com.section11.listingforge.error.ResourceNotFoundException
import com.section11.listingforge.etsy.EtsyApi
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Route-level tests for the Task 9 submit pipeline (/api/listings and its
 * image/file sub-routes). A dedicated fake EtsyApi stands in for
 * EtsyApiClient so ownership (404) and Etsy-side validation (400) can be
 * exercised without a real Etsy call - same style as TemplateRoutesTest's
 * ShopPerUserFakeEtsyApi.
 */
class ListingRoutesTest {

    /** Only listingId 1 "exists"; createDraftListing always mints listingId 42. */
    private class SubmitPipelineFakeEtsyApi(private val rejectDraft: Boolean = false) : EtsyApi {
        var lastImageBytes: ByteArray? = null
        var lastImageRank: Int? = null
        var lastFileBytes: ByteArray? = null
        var lastFileName: String? = null

        override suspend fun getMe(userId: String) = error("not used by listing routes")
        override suspend fun getShop(userId: String): ShopResponse = error("not used by listing routes")
        override suspend fun getTaxonomy(): List<TaxonomyNodeResponse> = error("not used by listing routes")

        override suspend fun createDraftListing(userId: String, listing: ListingRequest): ListingResponse {
            if (rejectDraft) throw InvalidRequestException("Etsy rejected the listing: price too low")
            return ListingResponse(listingId = 42, state = "draft", editUrl = "https://www.etsy.com/your/shops/me/listing-editor/edit/42")
        }

        override suspend fun uploadListingImage(
            userId: String,
            listingId: Long,
            image: ByteArray,
            filename: String,
            rank: Int,
        ): ListingImageResponse {
            if (listingId != EXISTING_LISTING_ID) throw ResourceNotFoundException("Listing $listingId not found")
            lastImageBytes = image
            lastImageRank = rank
            return ListingImageResponse(imageId = 900, rank = rank)
        }

        override suspend fun uploadListingFile(
            userId: String,
            listingId: Long,
            file: ByteArray,
            filename: String,
        ): ListingFileResponse {
            if (listingId != EXISTING_LISTING_ID) throw ResourceNotFoundException("Listing $listingId not found")
            lastFileBytes = file
            lastFileName = filename
            return ListingFileResponse(fileId = 700)
        }
    }

    private fun Application.testModule(userResolver: UserResolver, etsy: EtsyApi) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(StatusPages) {
            exception<NotAuthenticatedException> { call, cause ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to cause.message))
            }
            exception<InvalidRequestException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to cause.message))
            }
            exception<ResourceNotFoundException> { call, cause ->
                call.respond(HttpStatusCode.NotFound, mapOf("error" to cause.message))
            }
        }
        routing { listingRoutes(etsy, userResolver) }
    }

    private val draftRequestBody = """
        {
          "title": "Floral Seamless Pattern",
          "description": "Hand-drawn seamless floral pattern.",
          "price": "4.50",
          "quantity": "999",
          "tags": ["floral", "seamless"],
          "whoMade": "i_did",
          "whenMade": "made_to_order",
          "taxonomyId": 123
        }
    """.trimIndent()

    @Test
    fun `POST api listings creates a draft and returns 201`() = testApplication {
        application { testModule(UserResolver { "user-a" }, SubmitPipelineFakeEtsyApi()) }

        val response = client.post("/api/listings") {
            contentType(ContentType.Application.Json)
            setBody(draftRequestBody)
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(
            """{"listingId":42,"state":"draft","editUrl":"https://www.etsy.com/your/shops/me/listing-editor/edit/42"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `POST api listings is 401 when the caller resolves to no user`() = testApplication {
        application { testModule(UserResolver { null }, SubmitPipelineFakeEtsyApi()) }

        val response = client.post("/api/listings") {
            contentType(ContentType.Application.Json)
            setBody(draftRequestBody)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST api listings passes an Etsy 400 through with its message`() = testApplication {
        application { testModule(UserResolver { "user-a" }, SubmitPipelineFakeEtsyApi(rejectDraft = true)) }

        val response = client.post("/api/listings") {
            contentType(ContentType.Application.Json)
            setBody(draftRequestBody)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "price too low")
    }

    @Test
    fun `POST api listings images uploads and returns imageId and rank`() = testApplication {
        val etsy = SubmitPipelineFakeEtsyApi()
        application { testModule(UserResolver { "user-a" }, etsy) }

        val response = client.submitFormWithBinaryData(
            url = "/api/listings/$EXISTING_LISTING_ID/images",
            formData = formData {
                append("rank", "1")
                append("image", byteArrayOf(1, 2, 3, 4), fileNameHeaders("image.jpg"))
            },
        )

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("""{"imageId":900,"rank":1}""", response.bodyAsText())
        assertEquals(1, etsy.lastImageRank)
        assertEquals(4, etsy.lastImageBytes?.size)
    }

    @Test
    fun `POST api listings images is 404 for a listing that does not belong to the caller`() = testApplication {
        application { testModule(UserResolver { "user-a" }, SubmitPipelineFakeEtsyApi()) }

        val response = client.submitFormWithBinaryData(
            url = "/api/listings/999/images",
            formData = formData {
                append("rank", "1")
                append("image", byteArrayOf(1, 2, 3), fileNameHeaders("image.jpg"))
            },
        )

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST api listings images is 400 when the rank field is missing`() = testApplication {
        application { testModule(UserResolver { "user-a" }, SubmitPipelineFakeEtsyApi()) }

        val response = client.submitFormWithBinaryData(
            url = "/api/listings/$EXISTING_LISTING_ID/images",
            formData = formData {
                append("image", byteArrayOf(1, 2, 3), fileNameHeaders("image.jpg"))
            },
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST api listings file uploads and returns fileId`() = testApplication {
        val etsy = SubmitPipelineFakeEtsyApi()
        application { testModule(UserResolver { "user-a" }, etsy) }

        val response = client.submitFormWithBinaryData(
            url = "/api/listings/$EXISTING_LISTING_ID/file",
            formData = formData {
                append("name", "pattern-kit.zip")
                append("file", byteArrayOf(5, 6, 7), fileNameHeaders("pattern-kit.zip"))
            },
        )

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("""{"fileId":700}""", response.bodyAsText())
        assertEquals("pattern-kit.zip", etsy.lastFileName)
    }

    @Test
    fun `POST api listings file is 404 for a listing that does not belong to the caller`() = testApplication {
        application { testModule(UserResolver { "user-a" }, SubmitPipelineFakeEtsyApi()) }

        val response = client.submitFormWithBinaryData(
            url = "/api/listings/999/file",
            formData = formData {
                append("name", "pattern-kit.zip")
                append("file", byteArrayOf(5, 6, 7), fileNameHeaders("pattern-kit.zip"))
            },
        )

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST api listings file is 401 when the caller resolves to no user`() = testApplication {
        application { testModule(UserResolver { null }, SubmitPipelineFakeEtsyApi()) }

        val response = client.submitFormWithBinaryData(
            url = "/api/listings/$EXISTING_LISTING_ID/file",
            formData = formData {
                append("name", "pattern-kit.zip")
                append("file", byteArrayOf(5, 6, 7), fileNameHeaders("pattern-kit.zip"))
            },
        )

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    /**
     * A bare `append(key, ByteArray)` produces a multipart FormItem, not a
     * FileItem - the CIO parser only classifies a part as a file when its
     * Content-Disposition carries `filename`, exactly like a real browser/app
     * upload does. Route parsing (receiveImageUpload/receiveFileUpload) only
     * looks at FileItem, so every binary part in this test needs this header.
     */
    private fun fileNameHeaders(name: String): Headers =
        Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$name\"") }

    private companion object {
        const val EXISTING_LISTING_ID = 1L
    }
}
