package com.wuming.android.tv.search

import com.wuming.android.tv.api.config.SiteSourceAdapter
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.utils.SiteRanker
import com.wuming.android.tv.utils.SearchResultMerger

class FastSearchCoordinator {
    private val shared = com.wuming.tv.core.search.SourceSearchCoordinator<Site, Vod>(
        { it.name }, { it.key }, { sites, enabled -> SiteSourceAdapter.catalog().searchable(sites, enabled) },
        { SiteRanker.get().rankByPerformance(it) }, { it.site.key }, { it.vodName }, SearchResultMerger::merge)
    fun begin(sites: List<Site>, enabledSources: Map<String, String>?): List<Site> = shared.begin(sites, enabledSources?.keys)
    fun setSearchQuery(query: String) = shared.setSearchQuery(query)
    fun reset() = shared.reset()
    fun showAllSources() = shared.showAllSources()
    fun add(items: List<Vod>): Set<String> = shared.add(items)
    fun selectSource(sourceName: String): List<Vod>? = shared.selectSource(sourceName)
    fun allResults(): List<Vod> = shared.allResults()
    fun sourceName(key: String): String = shared.sourceName(key)
    fun sourceInsertionPosition(key: String, displayedNames: List<String>): Int = shared.sourceInsertionPosition(key, displayedNames)
    fun rankingComparator(): Comparator<Vod> = shared.rankingComparator()
}
