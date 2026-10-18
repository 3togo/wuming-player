package com.wuming.tv.core.library

fun interface ContentRanker {
    fun rank(key: String): Int
}

interface LibraryItemAccess<T> {
    fun key(item: T): String
    fun sourceKey(item: T): String
}

/** Shared visibility and ordering policy for history and favorites. */
class UserLibraryPolicy<T>(private val access: LibraryItemAccess<T>) {

    fun visibleAndRanked(
        items: List<T>?,
        availableSourceKeys: Set<String>?,
        ranker: ContentRanker,
    ): List<T> = items.orEmpty()
        .asSequence()
        .filter { availableSourceKeys.isNullOrEmpty() || access.sourceKey(it) in availableSourceKeys }
        .map { Ranked(it, ranker.rank(access.key(it))) }
        .filter { it.rank >= 0 }
        .sortedByDescending { it.rank }
        .map { it.item }
        .toList()

    private data class Ranked<T>(val item: T, val rank: Int)
}
