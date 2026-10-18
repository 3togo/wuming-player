package com.wuming.android.tv.utils

import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.search.VodSearchResultAdapter

/** Compatibility facade over generic duplicate-result policy. */
object SearchResultMerger {
    @JvmStatic
    fun merge(items: List<Vod>): List<Vod> = VodSearchResultAdapter.policy().merge(items)
}
