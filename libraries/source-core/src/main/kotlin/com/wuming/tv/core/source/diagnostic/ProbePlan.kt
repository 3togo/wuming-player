package com.wuming.tv.core.source.diagnostic

import java.time.Duration
import java.util.EnumSet

class ProbePlan(
    stages: Set<ProbeStage>?,
    searchKeyword: String?,
    sampleCount: Int,
    stageTimeout: Duration?,
    retries: Int
) {
    val stages: Set<ProbeStage> = java.util.Set.copyOf(stages ?: emptySet())
    val searchKeyword: String = searchKeyword?.trim() ?: ""
    val sampleCount: Int = sampleCount.coerceIn(1, 5)
    val stageTimeout: Duration = stageTimeout ?: Duration.ofSeconds(15)
    val retries: Int = retries.coerceIn(0, 3)

    init {
        require(this.stageTimeout.let { !it.isZero && !it.isNegative }) { "Probe timeout must be positive" }
    }

    fun stages(): Set<ProbeStage> = stages
    fun searchKeyword(): String = searchKeyword
    fun sampleCount(): Int = sampleCount
    fun stageTimeout(): Duration = stageTimeout
    fun retries(): Int = retries

    override fun equals(other: Any?): Boolean =
        other is ProbePlan && stages == other.stages &&
        searchKeyword == other.searchKeyword &&
        sampleCount == other.sampleCount &&
        stageTimeout == other.stageTimeout &&
        retries == other.retries

    override fun hashCode(): Int = listOf(stages, searchKeyword, sampleCount, stageTimeout, retries).hashCode()

    override fun toString(): String = "ProbePlan(stages=$stages, searchKeyword=$searchKeyword, sampleCount=$sampleCount, stageTimeout=$stageTimeout, retries=$retries)"

    companion object {
        @JvmStatic
        fun standard(keyword: String): ProbePlan =
            ProbePlan(EnumSet.allOf(ProbeStage::class.java), keyword, 2, Duration.ofSeconds(15), 1)
    }
}
