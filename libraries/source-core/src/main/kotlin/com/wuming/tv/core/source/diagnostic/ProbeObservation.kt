package com.wuming.tv.core.source.diagnostic

class ProbeObservation(
    successful: Boolean,
    failureCode: ProbeFailureCode?,
    summary: String?,
    evidence: Map<String, String>?
) {
    val successful: Boolean = successful
    val failureCode: ProbeFailureCode = failureCode ?: ProbeFailureCode.NONE
    val summary: String = DiagnosticSanitizer.sanitizeText(summary)
    val evidence: Map<String, String> = DiagnosticSanitizer.sanitize(evidence)

    fun successful(): Boolean = successful
    fun failureCode(): ProbeFailureCode = failureCode
    fun summary(): String = summary
    fun evidence(): Map<String, String> = evidence

    override fun equals(other: Any?): Boolean =
        other is ProbeObservation && successful == other.successful &&
        failureCode == other.failureCode &&
        summary == other.summary &&
        evidence == other.evidence

    override fun hashCode(): Int = listOf(successful, failureCode, summary, evidence).hashCode()

    override fun toString(): String = "ProbeObservation(successful=$successful, failureCode=$failureCode, summary=$summary, evidence=$evidence)"

    companion object {
        @JvmStatic
        fun pass(summary: String, evidence: Map<String, String>): ProbeObservation =
            ProbeObservation(true, ProbeFailureCode.NONE, summary, evidence)

        @JvmStatic
        fun fail(code: ProbeFailureCode, summary: String): ProbeObservation =
            ProbeObservation(false, code, summary, emptyMap())
    }
}
