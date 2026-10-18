package com.wuming.android.tv.search

import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.api.config.SiteSourceAdapter
import com.wuming.android.tv.bean.Collect
import com.wuming.android.tv.bean.SearchFilter
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.utils.CollectPageMerger
import com.wuming.android.tv.utils.SiteRanker
import com.wuming.android.tv.utils.VideoRankManager
import com.wuming.android.tv.utils.VodScorer
import java.util.Comparator

/** Shared result, ranking, filtering, and pagination policy for collection screens. */
object CollectionWorkflow {
    @JvmStatic
    fun rankedSearchableSites(): List<Site> {
        val sites = SiteSourceAdapter.catalog().searchable(VodConfig.get().sites, null)
        return SiteRanker.get().rankByPerformance(sites)
    }

    @JvmStatic
    fun mergeAndRank(existing: List<Vod>, incoming: List<Vod>): List<Vod> {
        val merged = mergeCandidates(existing, VideoRankManager.apply(incoming))
        val positions = sitePositions(rankedSearchableSites())
        com.wuming.tv.core.search.ResultCollections.rankBySource(merged, VideoRankManager::getRank, { it.site.key }, positions)
        return merged
    }

    /** Keep each playable provider entry; only repeated site + video IDs are duplicates. */
    @JvmStatic
    fun mergeCandidates(existing: List<Vod>, incoming: List<Vod>): MutableList<Vod> {
        return com.wuming.tv.core.search.ResultCollections.merge(existing, incoming) { it.siteKey + '\u0000' + it.id }
    }

    @JvmStatic
    fun filterAndSort(items: List<Vod>, filter: SearchFilter, keyword: String): List<Vod> {
        return com.wuming.tv.core.search.ResultCollections.filterAndSort(items, filter::matches,
            com.wuming.tv.core.search.ResultCollections.Sort.valueOf(filter.sort.name), { it.name }, { it.year },
            { filtered -> VodScorer.sortByRelevance(filtered, keyword) })
    }

    @JvmStatic
    fun pageQuery(collect: Collect?, fallback: String): String {
        return com.wuming.tv.core.search.ResultCollections.pageQuery(collect?.getList()?.firstOrNull()?.searchMatchedQuery, fallback)
    }

    @JvmStatic
    fun sitePositions(ranked: List<Site>): Map<String, Int> {
        return com.wuming.tv.core.search.ResultCollections.sitePositions(ranked) { it.key }
    }
}
