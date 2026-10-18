package com.wuming.tv.core.source

/** Additive observations recorded before a persisted baseline finishes loading. */
data class PerformanceTotals(
    val requests: Long, val successes: Long, val deadLinks: Long, val failures: Long,
    val totalSpeed: Double, val speedSamples: Long, val lastSpeed: Long,
    val lastSuccess: Long, val lastFailure: Long,
) {
    fun withBaseline(baseline: PerformanceTotals) = PerformanceTotals(
        requests + baseline.requests, successes + baseline.successes,
        deadLinks + baseline.deadLinks, failures + baseline.failures,
        totalSpeed + baseline.totalSpeed, speedSamples + baseline.speedSamples,
        if (speedSamples > 0) lastSpeed else baseline.lastSpeed,
        maxOf(lastSuccess, baseline.lastSuccess), maxOf(lastFailure, baseline.lastFailure),
    )
}
