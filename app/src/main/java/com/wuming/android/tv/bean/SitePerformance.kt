package com.wuming.android.tv.bean

import androidx.annotation.NonNull
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.wuming.tv.core.source.SourcePerformancePolicy

@Entity(indices = [
    androidx.room.Index(value = ["performanceScore"]),
    androidx.room.Index(value = ["reliabilityScore"]),
    androidx.room.Index(value = ["avgSpeed"])
])
class SitePerformance {

    @NonNull
    @PrimaryKey
    var siteKey: String = ""

    var totalRequests: Long = 0
    var successCount: Long = 0
    var deadLinkCount: Long = 0
    var failureCount: Long = 0
    var totalSpeed: Double = 0.0
    var speedSampleCount: Long = 0
    var lastSpeed: Long = 0
    var avgSpeed: Double = 0.0
    var lastSuccessTime: Long = 0
    var lastFailureTime: Long = 0
    var reliabilityScore: Double = 0.0
    var performanceScore: Double = 0.0
    var updatedAt: Long = 0

    constructor()

    @Ignore
    constructor(@NonNull siteKey: String) {
        this.siteKey = siteKey
        this.updatedAt = System.currentTimeMillis()
    }

    @Ignore
    @Synchronized
    fun recordSuccess(speedBytesPerSec: Long) {
        totalRequests++
        successCount++
        lastSuccessTime = System.currentTimeMillis()

        if (speedBytesPerSec > 0) {
            lastSpeed = speedBytesPerSec
            totalSpeed += speedBytesPerSec
            speedSampleCount++
            avgSpeed = totalSpeed / speedSampleCount
        }

        calculateScores()
    }

    @Ignore
    @Synchronized
    fun recordDeadLink() {
        totalRequests++
        deadLinkCount++
        lastFailureTime = System.currentTimeMillis()
        calculateScores()
    }

    @Ignore
    @Synchronized
    fun recordFailure() {
        totalRequests++
        failureCount++
        lastFailureTime = System.currentTimeMillis()
        calculateScores()
    }

    @Ignore
    @Synchronized
    fun snapshot(): SitePerformance {
        val copy = SitePerformance()
        copy.siteKey = siteKey
        copy.totalRequests = totalRequests
        copy.successCount = successCount
        copy.deadLinkCount = deadLinkCount
        copy.failureCount = failureCount
        copy.totalSpeed = totalSpeed
        copy.speedSampleCount = speedSampleCount
        copy.lastSpeed = lastSpeed
        copy.avgSpeed = avgSpeed
        copy.lastSuccessTime = lastSuccessTime
        copy.lastFailureTime = lastFailureTime
        copy.reliabilityScore = reliabilityScore
        copy.performanceScore = performanceScore
        copy.updatedAt = updatedAt
        return copy
    }

    @Ignore
    @Synchronized
    fun adoptBaseline(baseline: SitePerformance) {
        fun totals(value: SitePerformance) = com.wuming.tv.core.source.PerformanceTotals(
            value.totalRequests, value.successCount, value.deadLinkCount, value.failureCount,
            value.totalSpeed, value.speedSampleCount, value.lastSpeed,
            value.lastSuccessTime, value.lastFailureTime,
        )
        val merged = totals(this).withBaseline(totals(baseline))
        totalRequests = merged.requests
        successCount = merged.successes
        deadLinkCount = merged.deadLinks
        failureCount = merged.failures
        totalSpeed = merged.totalSpeed
        speedSampleCount = merged.speedSamples
        lastSpeed = merged.lastSpeed
        lastSuccessTime = merged.lastSuccess
        lastFailureTime = merged.lastFailure
        avgSpeed = if (speedSampleCount == 0L) 0.0 else totalSpeed / speedSampleCount
        calculateScores()
    }

    private fun calculateScores() {
        if (totalRequests == 0L) {
            reliabilityScore = 0.0
            performanceScore = 0.0
            return
        }

        reliabilityScore = SourcePerformancePolicy.reliability(successCount, totalRequests)
        performanceScore = SourcePerformancePolicy.performance(reliabilityScore, avgSpeed)
        updatedAt = System.currentTimeMillis()
    }

    val successRate: Double
        get() = SourcePerformancePolicy.percentage(successCount, totalRequests)

    val deadLinkRate: Double
        get() = SourcePerformancePolicy.percentage(deadLinkCount, totalRequests)

    val failureRate: Double
        get() = SourcePerformancePolicy.percentage(failureCount, totalRequests)

    val isReliable: Boolean
        get() = SourcePerformancePolicy.reliable(successCount, totalRequests)

    val formattedSpeed: String
        get() = SourcePerformancePolicy.formatSpeed(avgSpeed, java.util.Locale.getDefault())

    override fun toString(): String {
        return "SitePerformance{" +
                "siteKey='" + siteKey + '\'' +
                ", totalRequests=" + totalRequests +
                ", successCount=" + successCount +
                ", deadLinkCount=" + deadLinkCount +
                ", failureCount=" + failureCount +
                ", avgSpeed=" + formattedSpeed +
                ", reliabilityScore=" + String.format(java.util.Locale.ROOT, "%.2f", reliabilityScore) +
                ", performanceScore=" + String.format(java.util.Locale.ROOT, "%.2f", performanceScore) +
                '}'
    }
}
