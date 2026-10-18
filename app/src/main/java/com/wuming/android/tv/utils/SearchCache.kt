package com.wuming.android.tv.utils

import com.wuming.android.tv.bean.Collect
import com.wuming.android.tv.search.TitleAliasRepository
import com.wuming.android.tv.setting.DisplaySettings
import com.wuming.tv.core.cache.ExpiringLruCache
import java.util.ArrayList
import java.util.concurrent.TimeUnit

open class SearchCache private constructor() {
    companion object {
        private const val CACHE_MAX_SIZE = 20
        private val CACHE_TTL = TimeUnit.MINUTES.toMillis(5)
        private val instance = SearchCache()

        @JvmStatic fun get(): SearchCache = instance
    }

    private val cache = ExpiringLruCache<String, List<Collect>>(CACHE_MAX_SIZE, CACHE_TTL)

    open fun put(
        keyword: String?,
        collects: List<Collect>,
    ) {
        cache.put(normalizeKey(keyword), ArrayList(collects))
    }

    open fun get(keyword: String?): MutableList<Collect>? {
        val entry = cache.get(normalizeKey(keyword))
        return if (entry ==
            null
        ) {
            null
        } else {
            ArrayList(entry)
        }
    }

    open fun clear() {
        cache.clear()
    }

    open fun trim() {
        cache.trim()
    }

    private fun normalizeKey(keyword: String?): String =
        if (keyword ==
            null
        ) {
            ""
        } else {
            TitleAliasRepository.get().canonicalKey(keyword) + (if (DisplaySettings.isStrictSearch()) "|strict" else "|broad")
        }
}
