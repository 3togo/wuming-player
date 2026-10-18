package com.wuming.tv.core.source

/**
 * Pure, platform-neutral index builders shared by the Android configuration loader and the
 * desktop client. Keyed lookups use a [LinkedHashMap] so insertion order is preserved and
 * duplicate keys overwrite instead of throwing (unlike [java.util.stream.Collectors.toMap]).
 */
object TvBoxSourceIndex {
    @JvmStatic
    fun <T> indexByKey(items: List<T>, key: (T) -> String): Map<String, T> {
        val map = LinkedHashMap<String, T>()
        for (item in items) map[key(item)] = item
        return map
    }

    @JvmStatic
    fun <T> indexByName(items: List<T>, name: (T) -> String): Map<String, T> = indexByKey(items, name)

    @JvmStatic
    fun <T> distinctByKey(items: List<T>, key: (T) -> String): List<T> {
        val seen = LinkedHashSet<String>()
        val result = ArrayList<T>()
        for (item in items) if (seen.add(key(item))) result.add(item)
        return result
    }
}
