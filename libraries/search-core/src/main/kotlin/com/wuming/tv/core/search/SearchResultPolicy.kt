package com.wuming.tv.core.search

import java.util.IdentityHashMap
import java.util.function.ToIntFunction

/** Annotation, strict matching, merging and relevance policy for client-owned result models. */
class SearchResultPolicy<T>(
    private val access: SearchResultAccess<T>,
    private val normalizer: TitleNormalizer,
) {

    fun annotate(items: List<T>?, plan: SearchQueryPlan, variant: SearchQueryPlan.Variant) {
        if (items == null) return
        for (item in items) {
            val identity = plan.identity()
            val confirmed = !identity.isEmpty() &&
                (matchesAny(access.title(item), identity.aliases()) ||
                    stronglyMatches(access.title(item), variant.text()))
            access.assignIdentity(
                item,
                if (confirmed) identity.canonicalId() else "",
                if (confirmed) identity.aliases() else emptyList(),
                plan.rawQuery(),
                variant.text(),
            )
        }
    }

    fun retainStrictMatches(items: MutableList<T>?) {
        items?.removeIf { !isStrictMatch(it) }
    }

    fun retainStrictMatches(
        items: MutableList<T>?,
        plan: SearchQueryPlan,
        variant: SearchQueryPlan.Variant,
    ) {
        items?.removeIf { !isStrictMatch(it, plan, variant) }
    }

    fun isStrictMatch(item: T?): Boolean {
        if (item == null) return false
        val title = access.title(item)
        return stronglyMatches(title, access.matchedQuery(item)) ||
            stronglyMatches(title, access.originalQuery(item)) ||
            matchesAny(title, access.aliases(item))
    }

    fun merge(items: List<T>?): List<T> {
        if (items == null) return emptyList()
        val merged = mutableListOf<T>()
        val canonical = HashMap<String, T>()
        val titles = HashMap<String, MutableList<T>>()
        // Scoped to this merge: aliases/results may change between incoming batches.
        val normalized = HashMap<String?, String>()
        fun key(value: String?): String = normalized.getOrPut(value) { normalizer.key(value) }
        fun compatibleMetadata(first: String?, second: String?): Boolean {
            if (first.isNullOrBlank() || second.isNullOrBlank()) return true
            val firstKey = key(first)
            return firstKey.isNotEmpty() && firstKey == key(second)
        }
        for (candidate in items) {
            val id = access.canonicalId(candidate)
            val titleKey = if (id.isEmpty()) key(access.title(candidate)) else ""
            val existing = if (id.isNotEmpty()) canonical[id] else {
                // Empty normalized titles never compare equivalent. Year/type wildcards
                // are not transitive, so retain first-match order within a title bucket.
                if (titleKey.isEmpty()) null else titles[titleKey]?.firstOrNull {
                    compatibleMetadata(access.year(it), access.year(candidate)) &&
                        compatibleMetadata(access.mediaType(it), access.mediaType(candidate))
                }
            }
            if (existing == null) {
                merged.add(candidate)
                if (id.isNotEmpty()) canonical[id] = candidate
                else if (titleKey.isNotEmpty()) titles.getOrPut(titleKey) { mutableListOf() }.add(candidate)
            } else {
                access.mergeSources(existing, candidate)
                mergeIdentity(existing, candidate)
                if (access.artwork(existing).isEmpty() && access.artwork(candidate).isNotEmpty()) {
                    access.setArtwork(existing, access.artwork(candidate))
                }
            }
        }
        return merged
    }

    fun relevanceScore(item: T, keyword: String?): Double {
        if (keyword.isNullOrBlank()) return 0.0
        val name = normalizer.comparisonText(access.title(item))
        val query = normalizer.comparisonText(keyword)
        if (name == query) return 1.0
        if (access.aliases(item).any { normalizer.equivalent(it, keyword) }) return 0.95
        if (access.canonicalId(item).isNotEmpty() &&
            normalizer.equivalent(access.originalQuery(item), keyword)) return 0.95
        if (name.contains(query)) return 0.8
        if (SearchTextMatcher.fuzzyMatch(name, query)) return 0.6
        if (SearchTextMatcher.containsAllWords(name, query)) return 0.5
        val similarity = SearchTextMatcher.similarity(name, query)
        return if (similarity >= 0.5) similarity * 0.5 else 0.0
    }

    fun compareRelevance(first: T, second: T, keyword: String?): Int =
        relevanceScore(second, keyword).compareTo(relevanceScore(first, keyword))

    /** Rank first, then relevance; all caches die when this stable sort returns. */
    fun sortByRelevance(items: MutableList<T>, keyword: String?, priority: ToIntFunction<T>) {
        if (items.size < 2) return
        val ranks = IdentityHashMap<T, Int>()
        val scores = IdentityHashMap<T, Double>()
        val text = HashMap<String?, String>()
        fun normalized(value: String?): String = text.getOrPut(value) { normalizer.comparisonText(value) }
        val query = if (keyword.isNullOrBlank()) null else normalized(keyword)
        val queryKey = query?.replace(" ", "")
        fun equivalent(value: String?): Boolean =
            !queryKey.isNullOrEmpty() && normalized(value).replace(" ", "") == queryKey
        fun score(item: T): Double = scores.getOrPut(item) {
            if (query == null) 0.0 else {
                val name = normalized(access.title(item))
                when {
                    name == query -> 1.0
                    access.aliases(item).any { equivalent(it) } -> 0.95
                    access.canonicalId(item).isNotEmpty() && equivalent(access.originalQuery(item)) -> 0.95
                    name.contains(query) -> 0.8
                    SearchTextMatcher.fuzzyMatch(name, query) -> 0.6
                    SearchTextMatcher.containsAllWords(name, query) -> 0.5
                    else -> SearchTextMatcher.similarity(name, query).let { if (it >= 0.5) it * 0.5 else 0.0 }
                }
            }
        }
        items.sortWith { first, second ->
            val rank = ranks.getOrPut(second) { priority.applyAsInt(second) }
                .compareTo(ranks.getOrPut(first) { priority.applyAsInt(first) })
            if (rank != 0) rank else score(second).compareTo(score(first))
        }
    }

    fun matchScore(name: String?, keyword: String?): Int {
        if (keyword.isNullOrBlank()) return 0
        val normalizedName = normalizer.comparisonText(name)
        val normalizedQuery = normalizer.comparisonText(keyword)
        return when {
            normalizedName == normalizedQuery -> 4
            normalizedName.contains(normalizedQuery) -> 3
            SearchTextMatcher.fuzzyMatch(normalizedName, normalizedQuery) -> 2
            SearchTextMatcher.containsAllWords(normalizedName, normalizedQuery) -> 1
            else -> 0
        }
    }

    fun stronglyMatches(title: String?, query: String?): Boolean {
        val titleText = normalizer.comparisonText(title)
        val queryText = normalizer.comparisonText(query)
        if (titleText.isEmpty() || queryText.isEmpty()) return false
        return titleText == queryText ||
            titleText.startsWith("$queryText ") ||
            queryText.startsWith("$titleText ")
    }

    private fun isStrictMatch(
        item: T,
        plan: SearchQueryPlan,
        variant: SearchQueryPlan.Variant,
    ): Boolean {
        val title = access.title(item)
        return stronglyMatches(title, plan.rawQuery()) ||
            stronglyMatches(title, variant.text()) ||
            matchesAny(title, plan.identity().aliases())
    }

    private fun matchesAny(title: String?, aliases: List<String>): Boolean =
        aliases.any { stronglyMatches(title, it) }

    private fun mergeIdentity(target: T, duplicate: T) {
        val aliases = (access.aliases(target) + access.aliases(duplicate)).distinct()
        access.assignIdentity(
            target,
            access.canonicalId(target).ifEmpty { access.canonicalId(duplicate) },
            aliases,
            access.originalQuery(target).ifEmpty { access.originalQuery(duplicate) },
            access.matchedQuery(target).ifEmpty { access.matchedQuery(duplicate) },
        )
    }
}
