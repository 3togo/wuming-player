package com.wuming.tv.core.library

/** Defines bounded history settings; storage and localized labels stay platform-specific. */
class HistoryRetentionPolicy(
    allowedLimits: IntArray = intArrayOf(20, 40, 60, 80, 100),
    val defaultLimit: Int = 40,
) {
    val allowedLimits: IntArray = allowedLimits.copyOf().also {
        require(it.isNotEmpty()) { "At least one history limit is required" }
        require(defaultLimit in it) { "Default history limit must be allowed" }
    }

    fun normalize(limit: Int): Int = if (limit in allowedLimits) limit else defaultLimit

    fun indexOf(limit: Int): Int = allowedLimits.indexOf(normalize(limit))

    fun limitAt(index: Int): Int? = allowedLimits.getOrNull(index)

    fun itemsToRemove(currentCount: Int, limit: Int): Int =
        (currentCount - normalize(limit)).coerceAtLeast(0)

    fun percentage(limit: Int): Int = normalize(limit) * 100 / allowedLimits.last()

    fun next(limit: Int): Int {
        val index = indexOf(limit)
        return allowedLimits[(index + 1) % allowedLimits.size]
    }

    fun previous(limit: Int): Int {
        val index = indexOf(limit)
        return allowedLimits[(index - 1 + allowedLimits.size) % allowedLimits.size]
    }
}
