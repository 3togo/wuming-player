package com.wuming.tv.core.source

import java.util.concurrent.TimeUnit

/** Pure sampling rules that turn transferred bytes into a recorded site speed. */
object SiteSpeedSamplePolicy {

    /** A sample shorter than this is too noisy to be stored as a measured speed. */
    val MIN_MEASURED_DURATION_MS = TimeUnit.SECONDS.toMillis(2)

    /** Live readouts stay silent until at least this much time has elapsed. */
    val MIN_LIVE_DURATION_MS = 1_000L

    enum class Outcome {
        /** Nothing was transferred, so the link is treated as dead. */
        DEAD_LINK,

        /** Data arrived, but too fast to report a trustworthy speed. */
        UNMEASURED_SUCCESS,

        /** Data arrived and the speed is worth recording. */
        MEASURED_SUCCESS
    }

    /** Bytes per second, guarding against overflow when scaling by 1000. */
    @JvmStatic
    fun bytesPerSecond(bytes: Long, durationMillis: Long): Long {
        if (bytes <= 0L || durationMillis <= 0L) return 0L
        if (bytes > Long.MAX_VALUE / 1000) {
            val whole = bytes / durationMillis
            if (whole > Long.MAX_VALUE / 1000) return Long.MAX_VALUE
            // Preserve the historical overflow-safe approximation for enormous counters.
            return whole * 1000
        }
        return (bytes * 1000) / durationMillis
    }

    /** Bytes per second for an in-flight sample, or `0` while it is too young. */
    @JvmStatic
    fun liveBytesPerSecond(bytes: Long, elapsedMillis: Long): Long {
        if (elapsedMillis < MIN_LIVE_DURATION_MS) return 0L
        return bytesPerSecond(bytes, elapsedMillis)
    }

    /** How a finished sample should be recorded. */
    @JvmStatic
    fun outcome(bytes: Long, durationMillis: Long): Outcome {
        if (bytes <= 0L) return Outcome.DEAD_LINK
        return if (durationMillis < MIN_MEASURED_DURATION_MS)
            Outcome.UNMEASURED_SUCCESS
        else Outcome.MEASURED_SUCCESS
    }
}
