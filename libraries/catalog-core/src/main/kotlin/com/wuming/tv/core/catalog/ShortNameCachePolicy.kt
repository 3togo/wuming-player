package com.wuming.tv.core.catalog

object ShortNameCachePolicy {

    fun interface Shortener {
        fun shorten(original: String, context: String): String
    }

    @JvmStatic
    fun evictIfNeeded(map: MutableMap<String, String>, maxEntries: Int) {
        if (map.size <= maxEntries) return
        val it = map.keys.iterator()
        while (map.size > maxEntries && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    @JvmStatic
    fun normalize(name: String?): String = name?.trim() ?: ""
}
