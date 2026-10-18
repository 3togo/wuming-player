package com.wuming.android.tv.search

import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.bean.Vod
import com.wuming.tv.core.search.SearchResultAccess
import com.wuming.tv.core.search.SearchResultPolicy

/** Maps the Android application's [Vod] bean to platform-neutral search policy. */
object VodSearchResultAdapter : SearchResultAccess<Vod> {
    private val POLICY = SearchResultPolicy(VodSearchResultAdapter, TitleNormalizer.core())

    @JvmStatic
    fun policy(): SearchResultPolicy<Vod> = POLICY

    override fun title(item: Vod): String = item.name
    override fun year(item: Vod): String = item.year
    override fun mediaType(item: Vod): String = item.typeName
    override fun artwork(item: Vod): String = item.pic
    override fun canonicalId(item: Vod): String = item.searchCanonicalId
    override fun aliases(item: Vod): List<String> = item.searchAliases
    override fun originalQuery(item: Vod): String = item.searchOriginalQuery
    override fun matchedQuery(item: Vod): String = item.searchMatchedQuery

    override fun assignIdentity(item: Vod, canonicalId: String, aliases: List<String>, originalQuery: String, matchedQuery: String) {
        item.setSearchIdentity(canonicalId, aliases, originalQuery, matchedQuery)
    }

    override fun mergeSources(target: Vod, duplicate: Vod) {
        target.addSourceSite(duplicate.site)
        for (source in duplicate.sourceSites) target.addSourceSite(source)
    }

    override fun setArtwork(item: Vod, artwork: String) {
        item.setPic(artwork)
    }
}
