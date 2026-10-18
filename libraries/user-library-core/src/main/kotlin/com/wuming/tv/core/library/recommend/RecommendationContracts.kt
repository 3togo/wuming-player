package com.wuming.tv.core.library.recommend

/**
 * Read-only view of one durable playback session used for preference learning.
 * The Android [com.wuming.android.tv.bean.ViewingRecord] implements this directly;
 * the desktop client adapts its JSON record to it.
 */
interface ViewingRecord {
    val key: String
    val completed: Boolean
    val watchedDurationMillis: Long
    val lastWatchedAtMillis: Long
    val typeName: String
    val year: String
    val area: String
    val director: String
    val actor: String
    val content: String
}

/**
 * Read-only view of a user favorite (keep) used as an additional preference signal.
 * Favorites carry stronger intent than passive viewing history.
 */
interface FavoriteSignal {
    val key: String
    val typeName: String
    val year: String
    val area: String
    val director: String
    val actor: String
    val content: String
}
