package com.wuming.tv.core.media.subtitle

import java.util.concurrent.ConcurrentHashMap

/** Registry and failure-isolated coordinator for subtitle providers. */
class SubtitleSearchEngine {
    private val providers = ConcurrentHashMap<String, SubtitleProvider>()

    fun register(provider: SubtitleProvider?): SubtitleSearchEngine {
        require(provider != null && !provider.id().isNullOrBlank()) {
            "Subtitle provider ID is required"
        }
        providers[provider.id()] = provider
        return this
    }

    fun unregister(providerId: String?) {
        if (providerId != null) providers.remove(providerId)
    }

    fun isEmpty(): Boolean = providers.isEmpty()

    fun search(query: SubtitleQuery): List<SubtitleSearchResult> {
        val snapshot = providers.values.sortedBy { it.id() }
        val results = ArrayList<SubtitleSearchResult>(snapshot.size)
        for (provider in snapshot) {
            try {
                results.add(SubtitleSearchResult(provider.id(), provider.search(query), ""))
            } catch (error: Exception) {
                results.add(SubtitleSearchResult(provider.id(), SubtitlePage.empty(query.page), error.javaClass.simpleName))
            }
        }
        return java.util.List.copyOf(results)
    }

    @Throws(Exception::class)
    fun resolve(candidate: SubtitleCandidate): SubtitleCandidate {
        val provider = providers[candidate.providerId] ?: throw IllegalArgumentException("Unknown subtitle provider")
        val resolved = provider.resolve(candidate)
        check(provider.id() == resolved.providerId) {
            "Provider returned an invalid subtitle candidate"
        }
        return resolved
    }
}
