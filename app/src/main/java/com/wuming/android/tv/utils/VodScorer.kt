package com.wuming.android.tv.utils

import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.search.VodSearchResultAdapter

/** Android ranking adapter: persisted user rank precedes generic relevance score. */
object VodScorer {
    @JvmStatic
    fun score(vod: Vod, keyword: String): Double =
        VodSearchResultAdapter.policy().relevanceScore(vod, keyword)

    @JvmStatic
    fun compareScore(first: Vod, second: Vod, keyword: String): Int {
        val rank = Integer.compare(VideoRankManager.getRank(second), VideoRankManager.getRank(first))
        return if (rank != 0) rank
        else VodSearchResultAdapter.policy().compareRelevance(first, second, keyword)
    }

    @JvmStatic
    fun sortByRelevance(items: MutableList<Vod>, keyword: String) {
        VodSearchResultAdapter.policy().sortByRelevance(items, keyword, VideoRankManager::getRank)
    }

    @JvmStatic
    fun matchScore(name: String, keyword: String): Int =
        VodSearchResultAdapter.policy().matchScore(name, keyword)
}
