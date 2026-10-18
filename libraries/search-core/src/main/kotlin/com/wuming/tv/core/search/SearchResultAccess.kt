package com.wuming.tv.core.search

/**
 * Maps a client-owned search result model to the fields used by generic search policy.
 * Platform models do not need to implement a core interface themselves.
 */
interface SearchResultAccess<T> {
    fun title(item: T): String
    fun year(item: T): String
    fun mediaType(item: T): String
    fun artwork(item: T): String
    fun canonicalId(item: T): String
    fun aliases(item: T): List<String>
    fun originalQuery(item: T): String
    fun matchedQuery(item: T): String

    fun assignIdentity(
        item: T,
        canonicalId: String,
        aliases: List<String>,
        originalQuery: String,
        matchedQuery: String,
    )

    /** Merges client-specific source provenance from [duplicate] into [target]. */
    fun mergeSources(target: T, duplicate: T)

    fun setArtwork(item: T, artwork: String)
}
