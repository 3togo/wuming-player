// Frozen pre-index policy for differential regression checks; test-only.
package com.wuming.tv.core.search

/** Annotation, strict matching, merging and relevance policy for client-owned result models. */
class LegacySearchResultPolicy<T>(
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
        for (candidate in items) {
            val existing = merged.firstOrNull { sameIdentity(it, candidate) }
            if (existing == null) {
                merged.add(candidate)
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

    private fun sameIdentity(first: T, second: T): Boolean {
        val firstId = access.canonicalId(first)
        val secondId = access.canonicalId(second)
        if (firstId.isNotEmpty() || secondId.isNotEmpty()) {
            return firstId.isNotEmpty() && firstId == secondId
        }
        return normalizer.equivalent(access.title(first), access.title(second)) &&
            compatible(access.year(first), access.year(second)) &&
            compatible(access.mediaType(first), access.mediaType(second))
    }

    private fun compatible(first: String?, second: String?): Boolean =
        first.isNullOrBlank() || second.isNullOrBlank() || normalizer.equivalent(first, second)

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
