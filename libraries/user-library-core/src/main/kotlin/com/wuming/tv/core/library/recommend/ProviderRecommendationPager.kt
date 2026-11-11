package com.wuming.tv.core.library.recommend

import com.wuming.tv.core.catalog.CatalogPage
import com.wuming.tv.core.catalog.CatalogResultPager

/** Recommendation pages may be reused only while their seed snapshot and privacy scope match. */
class ProviderRecommendationPager {
    private val session = ProviderRecommendationSession()
    private var identity: Identity? = null
    private var pager = CatalogResultPager()

    @Synchronized
    fun loadThrough(scope: String, page: Int, size: Int, visibleNames: List<String>, privateViewing: Boolean,
        fetch: (Int, List<String>) -> CatalogPage): CatalogPage {
        val seeds = session.seeds(scope, page, visibleNames, privateViewing)
        val next = Identity(scope, seeds, privateViewing)
        if (identity != next) {
            pager = CatalogResultPager()
            identity = next
        }
        return pager.loadThrough(page, size) { sourcePage -> fetch(sourcePage, seeds) }
    }

    private data class Identity(val scope: String, val seeds: List<String>, val privateViewing: Boolean)
}
