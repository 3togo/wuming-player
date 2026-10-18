package com.wuming.tv.core.source.diagnostic

class ProbeResult(
    sourceKey: String?,
    stage: ProbeStage,
    status: ProbeStatus,
    failureCode: ProbeFailureCode?,
    startedAtMs: Long,
    elapsedMs: Long,
    attempts: Int,
    summary: String?,
    evidence: Map<String, String>?
) {
    val sourceKey: String = sourceKey ?: ""
    val stage: ProbeStage = stage
    val status: ProbeStatus = status
    val failureCode: ProbeFailureCode = failureCode ?: ProbeFailureCode.NONE
    val startedAtMs: Long = startedAtMs
    val elapsedMs: Long = maxOf(0L, elapsedMs)
    val attempts: Int = maxOf(0, attempts)
    val summary: String = DiagnosticSanitizer.sanitizeText(summary)
    val evidence: Map<String, String> = DiagnosticSanitizer.sanitize(evidence)

    fun sourceKey(): String = sourceKey
    fun stage(): ProbeStage = stage
    fun status(): ProbeStatus = status
    fun failureCode(): ProbeFailureCode = failureCode
    fun startedAtMs(): Long = startedAtMs
    fun elapsedMs(): Long = elapsedMs
    fun attempts(): Int = attempts
    fun summary(): String = summary
    fun evidence(): Map<String, String> = evidence

    override fun equals(other: Any?): Boolean =
        other is ProbeResult && sourceKey == other.sourceKey &&
        stage == other.stage &&
        status == other.status &&
        failureCode == other.failureCode &&
        startedAtMs == other.startedAtMs &&
        elapsedMs == other.elapsedMs &&
        attempts == other.attempts &&
        summary == other.summary &&
        evidence == other.evidence

    override fun hashCode(): Int = listOf(sourceKey, stage, status, failureCode, startedAtMs, elapsedMs, attempts, summary, evidence).hashCode()

    override fun toString(): String = "ProbeResult(sourceKey=$sourceKey, stage=$stage, status=$status, failureCode=$failureCode, startedAtMs=$startedAtMs, elapsedMs=$elapsedMs, attempts=$attempts, summary=$summary, evidence=$evidence)"
}
