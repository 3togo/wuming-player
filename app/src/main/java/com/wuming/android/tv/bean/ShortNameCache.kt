package com.wuming.android.tv.bean

import android.util.Log
import com.wuming.android.tv.App
import com.wuming.tv.core.catalog.ShortNameCachePolicy
import com.wuming.tv.core.catalog.ShortNameCachePolicy.Shortener
import com.github.catvod.utils.Prefers
import com.google.gson.reflect.TypeToken
import java.util.LinkedHashMap

internal class ShortNameCache(
    private val tag: String,
    private val key: String,
    private val maxEntries: Int,
    private val shortener: Shortener
) {

    private val lock = Any()

    @Volatile
    private var cache: MutableMap<String, String>? = null

    fun compute(name: String, context: String): String {
        val original = ShortNameCachePolicy.normalize(name)
        if (original.isEmpty()) return ""
        val snapshot = cache
        if (snapshot != null) {
            val cached = snapshot[original]
            if (cached != null) return cached
        }
        synchronized(lock) {
            val map = cacheLocked()
            val cached = map[original]
            if (cached != null) return cached
            val shortName = shortener.shorten(original, ShortNameCachePolicy.normalize(context))
            map[original] = shortName
            ShortNameCachePolicy.evictIfNeeded(map, maxEntries)
            persistLocked()
            return shortName
        }
    }

    fun lookup(name: String): String {
        val original = ShortNameCachePolicy.normalize(name)
        if (original.isEmpty()) return ""
        val snapshot = cache ?: return original
        return snapshot[original] ?: original
    }

    private fun cacheLocked(): MutableMap<String, String> {
        if (cache == null) {
            var decoded: LinkedHashMap<String, String>? = null
            try {
                decoded = App.gson().fromJson(Prefers.getString(key, "{}"), TYPE)
            } catch (e: Exception) {
                Log.d(tag, "Failed to decode short-name cache", e)
            }
            if (decoded == null) decoded = LinkedHashMap()
            ShortNameCachePolicy.evictIfNeeded(decoded, maxEntries)
            cache = decoded
        }
        return cache!!
    }

    private fun persistLocked() {
        try {
            Prefers.put(key, App.gson().toJson(cache))
        } catch (e: Exception) {
            Log.d(tag, "Failed to persist short-name cache", e)
        }
    }

    companion object {

        private val TYPE = object : TypeToken<LinkedHashMap<String, String>>() {}.type
    }
}
