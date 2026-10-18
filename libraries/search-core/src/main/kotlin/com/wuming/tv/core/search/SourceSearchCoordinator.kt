package com.wuming.tv.core.search

/** Source-grouped search state and ranking; hosts expose their source/result fields. */
class SourceSearchCoordinator<S, R>(
    private val sourceName: (S) -> String,
    private val sourceKey: (S) -> String,
    private val searchable: (List<S>, Set<String>?) -> List<S>,
    private val rankSources: (List<S>) -> List<S>,
    private val resultSource: (R) -> String,
    private val rawTitle: (R) -> String?,
    private val mergeResults: (List<R>) -> List<R>,
) {
    private val sourceKeysByName = HashMap<String, String>()
    private val resultsBySource = HashMap<String, MutableList<R>>()
    private val sourceRanks = HashMap<String, Int>()
    private var rankedSites = emptyList<S>()
    private var selectedSource = ""
    private var searchQuery = ""
    fun begin(sites: List<S>, enabledSources: Set<String>?): List<S> {
        reset()
        val sources = searchable(sites, enabledSources)
        sources.forEach { sourceKeysByName[sourceName(it)] = sourceKey(it) }
        rankedSites = rankSources(sources)
        rankedSites.forEachIndexed { index, source -> sourceRanks.putIfAbsent(sourceKey(source), index) }
        return rankedSites
    }
    fun setSearchQuery(query: String) { searchQuery = query.lowercase().trim() }
    fun reset() { sourceKeysByName.clear(); resultsBySource.clear(); sourceRanks.clear(); rankedSites = emptyList(); selectedSource = ""; searchQuery = "" }
    fun showAllSources() { selectedSource = "" }
    fun add(items: List<R>): Set<String> = LinkedHashSet<String>().apply {
        items.forEach { item -> val key = resultSource(item); resultsBySource.computeIfAbsent(key) { ArrayList() }.add(item); add(key) }
    }
    fun selectSource(name: String): List<R>? {
        val key = sourceKeysByName[name] ?: return null
        if (key.isEmpty() || selectedSource == key) return null
        selectedSource = key
        return resultsBySource.getOrDefault(key, emptyList())
    }
    fun allResults(): List<R> = if (selectedSource.isNotEmpty()) resultsBySource.getOrDefault(selectedSource, emptyList())
        else mergeResults(resultsBySource.values.flatMap { it })
    fun sourceName(key: String): String = sourceKeysByName.entries.firstOrNull { it.value == key }?.key ?: ""
    fun sourceInsertionPosition(key: String, displayed: List<String>): Int {
        val target = sitePosition(key)
        if (target == Int.MAX_VALUE) return displayed.size
        var position = minOf(1, displayed.size)
        for (i in 0 until target) if (displayed.contains(sourceName(sourceKey(rankedSites[i])))) position++
        return minOf(position, displayed.size)
    }
    fun rankingComparator(): Comparator<R> = Comparator { a, b ->
        val sites = sitePosition(resultSource(a)).compareTo(sitePosition(resultSource(b)))
        if (sites != 0) sites else titleScore(b).compareTo(titleScore(a))
    }
    private fun titleScore(item: R): Double {
        if (searchQuery.isEmpty()) return 0.0
        val title = rawTitle(item)?.lowercase()?.trim() ?: return 0.0
        return when { title == searchQuery -> 1.0; title.startsWith(searchQuery) -> 0.8; title.contains(searchQuery) -> 0.6
            else -> SearchTextMatcher.similarity(title, searchQuery) }
    }
    private fun sitePosition(key: String): Int = sourceRanks.getOrDefault(key, Int.MAX_VALUE)
}
