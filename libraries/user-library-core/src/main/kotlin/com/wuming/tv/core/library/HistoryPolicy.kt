package com.wuming.tv.core.library

import kotlin.math.abs

/** Persistence-independent rules for recent playback history. */
object HistoryPolicy {
    const val DEFAULT_SYNC_INTERVAL_MILLIS = 5_000L
    const val DEFAULT_MERGE_TOLERANCE_MILLIS = 10 * 60 * 1_000L

    @JvmStatic
    fun canSave(positionMillis: Long): Boolean = positionMillis > 0

    @JvmStatic
    @JvmOverloads
    fun canSync(
        nowMillis: Long,
        lastUpdateMillis: Long,
        intervalMillis: Long = DEFAULT_SYNC_INTERVAL_MILLIS,
    ): Boolean = nowMillis - lastUpdateMillis > intervalMillis

    @JvmStatic
    @JvmOverloads
    fun shouldMerge(
        existingKey: String?,
        existingDurationMillis: Long,
        incomingKey: String?,
        incomingDurationMillis: Long,
        force: Boolean,
        toleranceMillis: Long = DEFAULT_MERGE_TOLERANCE_MILLIS,
    ): Boolean {
        if (!force && existingKey == incomingKey) return false
        if (existingDurationMillis <= 0 || incomingDurationMillis <= 0) return true
        return abs(existingDurationMillis - incomingDurationMillis) <= toleranceMillis
    }
}
