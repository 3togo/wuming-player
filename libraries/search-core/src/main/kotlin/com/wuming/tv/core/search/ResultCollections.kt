package com.wuming.tv.core.search

/** Provider-identity collection merge, source ranking and presentation-independent
 * ordering. Host adapters expose fields and supply the shared relevance comparator. */
object ResultCollections {
    enum class Sort { RELEVANCE, NAME, YEAR }
    enum class Type { ALL, MOVIE, TV }

    @JvmStatic
    fun <T> appendUnique(existing: MutableList<T>, incoming: List<T>, key: (T) -> String): List<T> {
        val keys = existing.mapTo(HashSet(), key)
        val added = ArrayList<T>()
        for (item in incoming) if (keys.add(key(item))) { existing.add(item); added.add(item) }
        return added
    }

    @JvmStatic
    fun <T> merge(existing: List<T>, incoming: List<T>, key: (T) -> String): MutableList<T> =
        ArrayList<T>(existing.size + incoming.size).also { appendUnique(it, existing, key); appendUnique(it, incoming, key) }

    @JvmStatic
    fun <T> rankBySource(items: MutableList<T>, rank: (T) -> Int, site: (T) -> String, positions: Map<String, Int>) {
        items.sortWith { first, second ->
            val ranked = Integer.compare(rank(second), rank(first))
            if (ranked != 0) ranked else Integer.compare(positions.getOrDefault(site(first), Int.MAX_VALUE), positions.getOrDefault(site(second), Int.MAX_VALUE))
        }
    }

    @JvmStatic
    fun <S> sitePositions(sources: List<S>, key: (S) -> String): Map<String, Int> =
        HashMap<String, Int>().apply { sources.forEachIndexed { index, source -> put(key(source), index) } }

    @JvmStatic
    fun <T> filterAndSort(items: List<T>, predicate: (T) -> Boolean, sort: Sort,
                         name: (T) -> String, year: (T) -> String, relevance: (MutableList<T>) -> Unit): List<T> =
        items.filter(predicate).toMutableList().apply {
            when (sort) {
                Sort.RELEVANCE -> relevance(this)
                Sort.NAME -> sortWith(compareBy(name))
                Sort.YEAR -> sortWith(compareByDescending(year))
            }
        }

    @JvmStatic
    fun matchesType(type: Type, label: String): Boolean {
        val value = label.lowercase(java.util.Locale.getDefault())
        return when (type) {
            Type.ALL -> true
            Type.MOVIE -> value.contains("movie") || value.contains("film") || value.contains("电影")
            Type.TV -> value.contains("tv") || value.contains("series") || value.contains("剧") || value.contains("综艺")
        }
    }

    @JvmStatic
    fun pageQuery(matched: String?, fallback: String): String = if (matched.isNullOrEmpty()) fallback else matched
}
