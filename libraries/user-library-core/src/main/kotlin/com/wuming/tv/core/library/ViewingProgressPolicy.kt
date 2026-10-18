package com.wuming.tv.core.library

data class ViewingProgress(
    val watchedDurationMillis: Long,
    val lastWatchedAtMillis: Long,
    val endPositionMillis: Long,
    val mediaDurationMillis: Long,
    val completed: Boolean,
)

/** Pure session math. The platform decides when and where snapshots are stored. */
object ViewingProgressPolicy {
    const val DEFAULT_SAVE_INTERVAL_MILLIS = 10_000L
    const val DEFAULT_MAX_TICK_MILLIS = 15_000L
    const val DEFAULT_COMPLETION_RATIO = 0.9

    @JvmStatic
    @JvmOverloads
    fun elapsedWatchTime(
        playing: Boolean,
        previousElapsedMillis: Long,
        elapsedMillis: Long,
        maximumTickMillis: Long = DEFAULT_MAX_TICK_MILLIS,
    ): Long {
        val elapsed = (elapsedMillis - previousElapsedMillis).coerceAtLeast(0)
        return if (playing && elapsed <= maximumTickMillis) elapsed else 0
    }

    @JvmStatic
    @JvmOverloads
    fun update(
        watchedDurationMillis: Long,
        watchedDeltaMillis: Long,
        nowMillis: Long,
        positionMillis: Long,
        currentMediaDurationMillis: Long,
        durationMillis: Long,
        wasCompleted: Boolean,
        completionRatio: Double = DEFAULT_COMPLETION_RATIO,
    ): ViewingProgress {
        val mediaDuration = if (durationMillis > 0) durationMillis else currentMediaDurationMillis.coerceAtLeast(0)
        val endPosition = positionMillis.coerceAtLeast(0)
        val completed = wasCompleted || mediaDuration > 0 && endPosition >= mediaDuration * completionRatio
        return ViewingProgress(
            watchedDurationMillis + watchedDeltaMillis.coerceAtLeast(0),
            nowMillis,
            endPosition,
            mediaDuration,
            completed,
        )
    }

    @JvmStatic
    @JvmOverloads
    fun shouldPersist(
        watchedDurationMillis: Long,
        lastSavedWatchTimeMillis: Long,
        intervalMillis: Long = DEFAULT_SAVE_INTERVAL_MILLIS,
    ): Boolean = watchedDurationMillis - lastSavedWatchTimeMillis >= intervalMillis

    @JvmStatic
    fun matches(key: String?, episode: String?, otherKey: String?, otherEpisode: String?): Boolean =
        key.orEmpty() == otherKey.orEmpty() && episode.orEmpty() == otherEpisode.orEmpty()
}
