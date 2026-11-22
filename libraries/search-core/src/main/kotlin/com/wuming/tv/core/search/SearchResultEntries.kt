package com.wuming.tv.core.search

/** Keep selectable source entries distinct even when their titles or canonical identities match. */
object SearchResultEntries {
    fun <T> distinct(items: List<T>, sourceKey: (T) -> String, videoId: (T) -> String): List<T> =
        items.distinctBy { sourceKey(it) to videoId(it) }
}
