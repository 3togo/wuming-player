package com.wuming.android.tv.api.config

import com.wuming.android.tv.bean.Site
import com.wuming.tv.core.source.SourceAccess
import com.wuming.tv.core.source.SourceCatalogPolicy
import com.wuming.tv.core.source.SourceRankingPolicy

/** Maps Android's persisted/runtime Site model to generic source policy. */
class SiteSourceAdapter private constructor() : SourceAccess<Site> {
    override fun key(source: Site): String = source.key
    override fun displayName(source: Site): String = source.name
    override fun api(source: Site): String = source.api
    override fun extension(source: Site): String = source.ext
    override fun jar(source: Site): String = source.jar
    override fun type(source: Site): Int = source.type
    override fun timeoutMillis(source: Site): Long = source.timeout
    override fun hidden(source: Site): Boolean = source.isHide
    override fun indexable(source: Site): Boolean = source.isIndex
    override fun searchable(source: Site): Boolean = source.isSearchable
    override fun changeable(source: Site): Boolean = source.isChangeable
    override fun quickSearch(source: Site): Boolean = source.isQuickSearch
    override fun categories(source: Site): List<String> = source.categories
    override fun headers(source: Site): Map<String, String> = source.header

    companion object {
        private val INSTANCE = SiteSourceAdapter()
        private val CATALOG = SourceCatalogPolicy(INSTANCE)
        private val RANKING = SourceRankingPolicy(INSTANCE)

        @JvmStatic
        fun catalog(): SourceCatalogPolicy<Site> = CATALOG

        @JvmStatic
        fun ranking(): SourceRankingPolicy<Site> = RANKING
    }
}
