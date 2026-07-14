package com.section11.listingforge.etsy

import com.section11.listingforge.dto.TaxonomyNodeResponse

/**
 * Flattens Etsy's nested taxonomy tree into one list, building each node's
 * `path` from its ancestor names. Shared by EtsyApiClient (live) and
 * FakeEtsyApi (mock) so both produce identically shaped output.
 */
internal fun flattenTaxonomy(nodes: List<EtsyTaxonomyNode>): List<TaxonomyNodeResponse> {
    return nodes.flatMap { flattenNode(it, parentPath = null) }
}

private fun flattenNode(node: EtsyTaxonomyNode, parentPath: String?): List<TaxonomyNodeResponse> {
    val path = if (parentPath == null) node.name else "$parentPath > ${node.name}"
    val self = TaxonomyNodeResponse(id = node.id, name = node.name, path = path)
    return listOf(self) + node.children.flatMap { flattenNode(it, path) }
}
