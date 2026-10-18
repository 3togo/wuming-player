package com.wuming.android.tv.bean

import com.wuming.tv.core.cache.BoundedReferenceCache
import com.wuming.tv.core.cache.MemoryBudget
import java.util.Collections

open class FilterCache {
    private val cache = BoundedReferenceCache<String, List<Filter>>(MemoryBudget.current().scale(64, 128, 256))

    private object Loader {
        @Volatile var instance = FilterCache()
    }

    open fun put(result: Result?) {
        if (result == null) return
        result.getTypes().forEach { type -> get().cache.put(type.getTypeId(), type.getFilters()) }
    }

    companion object {
        private fun get(): FilterCache = Loader.instance

        @JvmStatic fun get(type: Class): List<Filter> = get(type.getTypeId())

        @JvmStatic fun get(typeId: String): List<Filter> = get().cache.get(typeId) ?: Collections.emptyList()

        @JvmStatic fun copy(typeId: String): List<Filter> {
            val filters = get().cache.get(typeId) ?: return Collections.emptyList()
            return filters.stream().map(Filter::copy).toList()
        }

        @JvmStatic fun clear(): FilterCache {
            get().cache.clear()
            return get()
        }
    }
}
