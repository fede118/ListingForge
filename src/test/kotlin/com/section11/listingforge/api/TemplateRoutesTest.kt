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
import com.section11.listingforge.template.TemplateFields
import com.section11.listingforge.template.TemplateRecord
import com.section11.listingforge.template.TemplateStore
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Route-level tests for /api/templates: auth, request validation, and JSON
 * shape. Persistence itself (round trip, shop scoping at the SQL layer) is
 * covered by SqliteTemplateStoreTest; this file uses a trivial in-memory
 * TemplateStore fake plus a fake EtsyApi whose getShop resolves a different
 * shop per userId, so the "another shop's template 404s" behavior can be
 * exercised end to end through the route.
 */
class TemplateRoutesTest {

    private class InMemoryTemplateStore : TemplateStore {
        private val records = mutableMapOf<Long, TemplateRecord>()
        private val nextId = AtomicLong(1)

        override fun create(shopId: Long, fields: TemplateFields): TemplateRecord {
            val now = Instant.now()
            val record = TemplateRecord(nextId.getAndIncrement(), shopId, fields, now, now)
            records[record.id] = record
            return record
        }

        override fun list(shopId: Long): List<TemplateRecord> =
            records.values.filter { it.shopId == shopId }

        override fun update(shopId: Long, id: Long, fields: TemplateFields): TemplateRecord? {
            val existing = records[id]?.takeIf { it.shopId == shopId } ?: return null
            val updated = existing.copy(fields = fields, updatedAt = Instant.now())
            records[id] = updated
            return updated
        }

        override fun delete(shopId: Long, id: Long): Boolean {
            val existing = records[id]?.takeIf { it.shopId == shopId } ?: return false
            records.remove(id)
            return true
        }
    }

    /** Resolves a fixed shop per userId, so different "signed-in" callers land in different shops. */
    private class ShopPerUserFakeEtsyApi(private val shopIdByUser: Map<String, Long>) : EtsyApi {
        override suspend fun getMe(userId: String) = error("not used by template routes")

        override suspend fun getShop(userId: String): ShopResponse {
            val shopId = shopIdByUser.getValue(userId)
            return ShopResponse(id = shopId, name = "Shop $shopId")
        }

        override suspend fun getTaxonomy(): List<TaxonomyNodeResponse> = error("not used by template routes")

        override suspend fun createDraftListing(userId: String, listing: ListingRequest): ListingResponse =
            error("not used by template routes")

        override suspend fun uploadListingImage(
            userId: String,
            listingId: Long,
            image: ByteArray,
            filename: String,
            rank: Int,
        ): ListingImageResponse = error("not used by template routes")

        override suspend fun uploadListingFile(
            userId: String,
            listingId: Long,
            file: ByteArray,
            filename: String,
        ): ListingFileResponse = error("not used by template routes")
    }

    private fun Application.testModule(userResolver: UserResolver, templates: TemplateStore) {
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
        val etsy = ShopPerUserFakeEtsyApi(mapOf("user-a" to 1L, "user-b" to 2L))
        routing { templateRoutes(templates, etsy, userResolver) }
    }

    private val requestBody = """
        {
          "name": "Seamless floral",
          "title": "Watercolor Floral Seamless Pattern",
          "description": "A hand-drawn seamless floral pattern.",
          "price": "4.50",
          "quantity": "999",
          "tags": ["floral", "seamless"],
          "whoMade": "i_did",
          "whenMade": "made_to_order",
          "taxonomyId": 123,
          "taxonomyPath": "Craft Supplies > Patterns",
          "specsText": "300 DPI, 12x12in"
        }
    """.trimIndent()

    @Test
    fun `full CRUD round trip for the signed-in shop`() = testApplication {
        val store = InMemoryTemplateStore()
        application { testModule(UserResolver { "user-a" }, store) }

        val createResponse = client.post("/api/templates") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val created = createResponse.bodyAsText()
        assertContains(created, "\"name\":\"Seamless floral\"")
        val id = Regex(""""id":(\d+)""").find(created)!!.groupValues[1]

        val listResponse = client.get("/api/templates")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        assertContains(listResponse.bodyAsText(), "\"name\":\"Seamless floral\"")

        val updateResponse = client.put("/api/templates/$id") {
            contentType(ContentType.Application.Json)
            setBody(requestBody.replace("Seamless floral", "Seamless floral v2"))
        }
        assertEquals(HttpStatusCode.OK, updateResponse.status)
        assertContains(updateResponse.bodyAsText(), "\"name\":\"Seamless floral v2\"")

        val deleteResponse = client.delete("/api/templates/$id")
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        val afterDelete = client.get("/api/templates")
        assertEquals("""{"templates":[]}""", afterDelete.bodyAsText())
    }

    @Test
    fun `GET api templates is 401 when the caller resolves to no user`() = testApplication {
        application { testModule(UserResolver { null }, InMemoryTemplateStore()) }

        val response = client.get("/api/templates")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST api templates rejects a blank name with 400`() = testApplication {
        application { testModule(UserResolver { "user-a" }, InMemoryTemplateStore()) }

        val response = client.post("/api/templates") {
            contentType(ContentType.Application.Json)
            setBody(requestBody.replace("\"name\": \"Seamless floral\"", "\"name\": \"   \""))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a template created under one shop is invisible to another shop`() {
        val store = InMemoryTemplateStore()
        var id = ""

        testApplication {
            application { testModule(UserResolver { "user-a" }, store) }
            val createResponse = client.post("/api/templates") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            id = Regex(""""id":(\d+)""").find(createResponse.bodyAsText())!!.groupValues[1]
        }

        // Same store, but this application instance resolves to shop 2 (user-b).
        testApplication {
            application { testModule(UserResolver { "user-b" }, store) }

            val listAsOtherShop = client.get("/api/templates")
            assertEquals("""{"templates":[]}""", listAsOtherShop.bodyAsText())

            val getOtherShopTemplate = client.put("/api/templates/$id") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            assertEquals(HttpStatusCode.NotFound, getOtherShopTemplate.status)

            val deleteOtherShopTemplate = client.delete("/api/templates/$id")
            assertEquals(HttpStatusCode.NotFound, deleteOtherShopTemplate.status)
        }
    }

    @Test
    fun `PUT api templates for a nonexistent id is 404`() = testApplication {
        application { testModule(UserResolver { "user-a" }, InMemoryTemplateStore()) }

        val response = client.put("/api/templates/999") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE api templates for a non-numeric id is 404`() = testApplication {
        application { testModule(UserResolver { "user-a" }, InMemoryTemplateStore()) }

        val response = client.delete("/api/templates/not-a-number")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
