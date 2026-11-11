package com.wuming.tv.core.catalog

import org.junit.Assert.*
import org.junit.Test

class RecommendationBridgeContractTest {
    @Test fun passesSeedsThroughGatewayCacheAndEvidenceWithoutStaleCategoryReuse() {
        var received = emptyList<String>()
        val observed = mutableSetOf<CatalogCapability>()
        val provider = object : CatalogSourceExecutor {
            override val source = CatalogSource("demo", "Douban")
            override val hostContract = CatalogHostContract.JVM
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = CatalogDetail()
            override fun play(flag: String, id: String) = CatalogPlayback()
            override fun categoryWithRecommendationSeeds(typeId: String, page: Int, filters: Map<String, String>, seeds: List<String>): CatalogPage {
                assertEquals("guess_you_like", typeId)
                assertEquals(mapOf("year" to "2026"), filters)
                received = seeds
                return CatalogPage(items = listOf(CatalogItem("demo", "id", seeds.joinToString())))
            }
        }
        val cache = CachingCatalogSourceExecutor(VerifyingCatalogSourceExecutor(provider, { observed += it }))
        val gateway = ExecutorCatalogGateway(listOf(cache))
        gateway.categoryWithRecommendationSeeds("demo", "guess_you_like", 1, mapOf("year" to "2026"), listOf("First"))
        assertEquals(listOf("First"), received)
        gateway.categoryWithRecommendationSeeds("demo", "guess_you_like", 1, mapOf("year" to "2026"), emptyList())
        assertEquals(emptyList<String>(), received)
        assertTrue(CatalogCapability.CATEGORY in observed)
        assertEquals(CatalogHostContract.JVM, gateway.hostContract("demo"))
        assertFalse(gateway.hostContract("demo").supports("searchContent(page)"))
        assertTrue(gateway.hostContract("demo").summary().contains("not exposed:"))
        assertFalse(CatalogHostContract.JVM.supports("proxy"))
        assertTrue(CatalogHostContract.GUARDED.supports("proxy"))
        cache.close()
    }
}
