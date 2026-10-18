package com.wuming.android.tv.utils

import com.wuming.android.tv.bean.Vod

object CollectPageMerger {
    /** Adds only unseen search results and returns the items safe to append to the visible grid. */
    @JvmStatic
    fun appendUnique(existing: MutableList<Vod>, incoming: List<Vod>): List<Vod> {
        return com.wuming.tv.core.search.ResultCollections.appendUnique(existing, incoming, ::key)
    }

    private fun key(vod: Vod): String = vod.siteKey + '\u0000' + vod.id
}
