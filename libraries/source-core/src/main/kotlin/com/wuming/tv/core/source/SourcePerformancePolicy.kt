package com.wuming.tv.core.source

import java.util.Locale

/** Pure calculations for persisted source-performance state. */
object SourcePerformancePolicy {
    private const val MAX_EXPECTED_SPEED = 10_000_000.0

    @JvmStatic
    fun reliability(successCount: Long, totalRequests: Long): Double =
        if (totalRequests <= 0) 0.0 else successCount.toDouble() / totalRequests

    @JvmStatic
    fun performance(reliability: Double, averageSpeed: Double): Double {
        val normalizedSpeed = (averageSpeed / MAX_EXPECTED_SPEED).coerceIn(0.0, 1.0)
        return reliability * 0.6 + normalizedSpeed * 0.4
    }

    @JvmStatic
    fun percentage(count: Long, totalRequests: Long): Double =
        if (totalRequests <= 0) 0.0 else count.toDouble() / totalRequests * 100.0

    @JvmStatic
    fun reliable(successCount: Long, totalRequests: Long): Boolean =
        percentage(successCount, totalRequests) >= 70.0

    @JvmStatic
    fun formatSpeed(averageSpeed: Double, locale: Locale): String {
        val speed = averageSpeed.toLong()
        return when {
            speed <= 0 -> "N/A"
            speed < 1024 -> "$speed B/s"
            speed < 1024 * 1024 -> String.format(locale, "%.1f KB/s", speed / 1024.0)
            else -> String.format(locale, "%.1f MB/s", speed / (1024.0 * 1024.0))
        }
    }
}
