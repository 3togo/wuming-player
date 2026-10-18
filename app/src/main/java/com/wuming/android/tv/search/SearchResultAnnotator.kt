package com.wuming.android.tv.search

import com.wuming.android.tv.bean.Vod
import com.wuming.tv.core.search.SearchQueryPlan

/** Compatibility facade over the platform-neutral search result policy. */
object SearchResultAnnotator {
    @JvmStatic
    fun annotate(items: List<Vod>, plan: SearchQueryPlan, variant: SearchQueryPlan.Variant) {
        VodSearchResultAdapter.policy().annotate(items, plan, variant)
    }

    @JvmStatic
    fun retainStrictMatches(items: List<Vod>) {
        @Suppress("UNCHECKED_CAST")
        VodSearchResultAdapter.policy().retainStrictMatches(items as MutableList<Vod>)
    }

    @JvmStatic
    fun retainStrictMatches(items: List<Vod>, plan: SearchQueryPlan, variant: SearchQueryPlan.Variant) {
        @Suppress("UNCHECKED_CAST")
        VodSearchResultAdapter.policy().retainStrictMatches(items as MutableList<Vod>, plan, variant)
    }
}
