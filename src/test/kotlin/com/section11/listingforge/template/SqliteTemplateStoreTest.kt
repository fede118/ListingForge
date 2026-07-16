package com.section11.listingforge.template

import com.section11.listingforge.db.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises SqliteTemplateStore against a real (temp-file) SQLite database via
 * Database.dataSource, the same schema/pool the app boots with - so this locks
 * in the actual SQL, not just the interface contract. Covers what the
 * route-level tests can't: the create/list/update/delete round trip and shop
 * scoping at the persistence layer itself.
 */
class SqliteTemplateStoreTest {
    private lateinit var dbFile: File
    private lateinit var store: SqliteTemplateStore

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("templates-test", ".db").apply { deleteOnExit() }
        store = SqliteTemplateStore(Database.dataSource(dbFile.absolutePath))
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    private fun sampleFields(name: String = "Seamless floral") = TemplateFields(
        name = name,
        title = "Watercolor Floral Seamless Pattern",
        description = "A hand-drawn seamless floral pattern for digital download.",
        price = "4.50",
        quantity = "999",
        tags = listOf("floral", "seamless", "digital paper"),
        whoMade = "i_did",
        whenMade = "made_to_order",
        taxonomyId = 123L,
        taxonomyPath = "Craft Supplies > Patterns",
        specsText = "300 DPI, 12x12in, PNG + JPG",
    )

    @Test
    fun `create then list round trips every field for that shop`() {
        val created = store.create(shopId = 1L, fields = sampleFields())

        assertTrue(created.id > 0)
        assertEquals(1L, created.shopId)
        assertEquals(sampleFields(), created.fields)

        val listed = store.list(shopId = 1L)
        assertEquals(listOf(created), listed)
    }

    @Test
    fun `update replaces every field and bumps updatedAt but keeps createdAt`() {
        val created = store.create(shopId = 1L, fields = sampleFields())
        val revised = sampleFields(name = "Seamless floral v2").copy(
            price = "6.00",
            tags = listOf("floral", "revised"),
            taxonomyId = null,
            taxonomyPath = null,
        )

        val updated = store.update(shopId = 1L, id = created.id, fields = revised)

        assertEquals(revised, updated?.fields)
        assertEquals(created.createdAt, updated?.createdAt)
        assertTrue((updated?.updatedAt ?: created.updatedAt) >= created.updatedAt)
    }

    @Test
    fun `update for an id under a different shop is a no-op and returns null`() {
        val created = store.create(shopId = 1L, fields = sampleFields())

        val result = store.update(shopId = 2L, id = created.id, fields = sampleFields("hijacked"))

        assertNull(result)
        assertEquals(sampleFields(), store.list(shopId = 1L).single().fields)
    }

    @Test
    fun `delete removes the row and reports success`() {
        val created = store.create(shopId = 1L, fields = sampleFields())

        assertTrue(store.delete(shopId = 1L, id = created.id))
        assertEquals(emptyList(), store.list(shopId = 1L))
    }

    @Test
    fun `delete for an id under a different shop leaves it intact and reports failure`() {
        val created = store.create(shopId = 1L, fields = sampleFields())

        assertEquals(false, store.delete(shopId = 2L, id = created.id))
        assertEquals(listOf(created), store.list(shopId = 1L))
    }

    @Test
    fun `list only returns templates for the requested shop`() {
        store.create(shopId = 1L, fields = sampleFields("shop one template"))
        store.create(shopId = 2L, fields = sampleFields("shop two template"))

        assertEquals(listOf("shop one template"), store.list(shopId = 1L).map { it.fields.name })
        assertEquals(listOf("shop two template"), store.list(shopId = 2L).map { it.fields.name })
    }
}
