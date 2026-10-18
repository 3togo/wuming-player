package com.wuming.tv.core.source.diagnostic

interface ProviderDiagnosticTarget {
    fun sourceKey(): String
    @Throws(Exception::class)
    fun probe(stage: ProbeStage, plan: ProbePlan, state: ProbeState): ProbeObservation
}
