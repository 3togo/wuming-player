package com.wuming.tv.core.library.recommend

import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogPage
import org.junit.Assert.*
import org.junit.Test

class ProviderRecommendationPagerTest {
    @Test fun reusesOnlyMatchingSeedsAndRestartsAfterDeletionOrPrivacyChange() {
        val pager = ProviderRecommendationPager()
        val calls = mutableListOf<Pair<Int, List<String>>>()
        fun load(page: Int, names: List<String>, privateMode: Boolean = false) =
            pager.loadThrough("source", page, 6, names, privateMode) { providerPage, seeds ->
                calls += providerPage to seeds.toList()
                CatalogPage((0..5).map { CatalogItem("source", "$providerPage:$it", seeds.joinToString()) }, providerPage, 3)
            }
        load(1, listOf("A", "B"))
        load(2, listOf("New", "A", "B"))
        assertEquals(listOf(1, 2), calls.map { it.first })
        assertEquals(listOf("A", "B"), calls.last().second)
        load(2, listOf("B"))
        assertEquals(listOf(1,2,1,2), calls.map { it.first })
        assertEquals(listOf("B"), calls.last().second)
        load(1, listOf("B"), true)
        assertEquals(emptyList<String>(), calls.last().second)
        val count = calls.size
        load(1, listOf("B"), true)
        assertEquals(count, calls.size)
        load(1, emptyList())
        assertEquals(count + 1, calls.size)
    }
}
