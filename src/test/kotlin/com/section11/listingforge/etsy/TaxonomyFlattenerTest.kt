package com.section11.listingforge.etsy

import com.section11.listingforge.dto.TaxonomyNodeResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class TaxonomyFlattenerTest {

    @Test
    fun `flattenTaxonomy emits every node with a self-only path for a single root`() {
        val nodes = listOf(EtsyTaxonomyNode(id = 1, name = "Craft Supplies & Tools"))

        val flattened = flattenTaxonomy(nodes)

        assertEquals(
            listOf(TaxonomyNodeResponse(1, "Craft Supplies & Tools", "Craft Supplies & Tools")),
            flattened,
        )
    }

    @Test
    fun `flattenTaxonomy builds a breadcrumb path per depth and preserves parent-before-child order`() {
        val tree = listOf(
            EtsyTaxonomyNode(
                id = 1,
                name = "Craft Supplies & Tools",
                children = listOf(
                    EtsyTaxonomyNode(
                        id = 11,
                        name = "Patterns & How To",
                        children = listOf(
                            EtsyTaxonomyNode(id = 111, name = "Digital Patterns"),
                            EtsyTaxonomyNode(id = 112, name = "Sewing Patterns"),
                        ),
                    ),
                ),
            ),
        )

        val flattened = flattenTaxonomy(tree)

        assertEquals(listOf(1L, 11L, 111L, 112L), flattened.map { it.id })
        assertEquals("Craft Supplies & Tools", flattened[0].path)
        assertEquals("Craft Supplies & Tools > Patterns & How To", flattened[1].path)
        assertEquals("Craft Supplies & Tools > Patterns & How To > Digital Patterns", flattened[2].path)
        assertEquals("Craft Supplies & Tools > Patterns & How To > Sewing Patterns", flattened[3].path)
    }

    @Test
    fun `flattenTaxonomy flattens multiple independent roots`() {
        val tree = listOf(
            EtsyTaxonomyNode(id = 1, name = "Craft Supplies & Tools"),
            EtsyTaxonomyNode(id = 2, name = "Paper & Party Supplies"),
        )

        val flattened = flattenTaxonomy(tree)

        assertEquals(listOf(1L, 2L), flattened.map { it.id })
    }
}
