package com.wuming.tv.core.source

class SourceMetric(
    private val totalRequests: Long,
    private val averageSpeed: Double,
    private val reliabilityScore: Double,
    private val performanceScore: Double,
) {
    fun totalRequests(): Long = totalRequests
    fun averageSpeed(): Double = averageSpeed
    fun reliabilityScore(): Double = reliabilityScore
    fun performanceScore(): Double = performanceScore
}
