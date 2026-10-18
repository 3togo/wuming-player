package com.wuming.android.tv.api.debug

import com.wuming.tv.core.source.diagnostic.DiagnosticSanitizer

class SpiderTrace(
    operation: SpiderOperation,
    status: SpiderTraceStatus,
    startedAtMs: Long,
    elapsedMs: Long,
    request: Map<String, String>?,
    response: String?,
    errorCode: String?,
    errorMessage: String?
) {
    val operation: SpiderOperation = operation
    val status: SpiderTraceStatus = status
    val startedAtMs: Long = startedAtMs
    val elapsedMs: Long = maxOf(0L, elapsedMs)
    val request: Map<String, String> = DiagnosticSanitizer.sanitize(request)
    val response: String = DiagnosticSanitizer.sanitizeText(response)
    val errorCode: String = errorCode ?: ""
    val errorMessage: String = DiagnosticSanitizer.sanitizeText(errorMessage)


    fun operation(): SpiderOperation = operation
    fun status(): SpiderTraceStatus = status
    fun startedAtMs(): Long = startedAtMs
    fun elapsedMs(): Long = elapsedMs
    fun request(): Map<String, String> = request
    fun response(): String = response
    fun errorCode(): String = errorCode
    fun errorMessage(): String = errorMessage

    override fun equals(other: Any?): Boolean =
        other is SpiderTrace && operation == other.operation &&
        status == other.status &&
        startedAtMs == other.startedAtMs &&
        elapsedMs == other.elapsedMs &&
        request == other.request &&
        response == other.response &&
        errorCode == other.errorCode &&
        errorMessage == other.errorMessage

    override fun hashCode(): Int = listOf(operation, status, startedAtMs, elapsedMs, request, response, errorCode, errorMessage).hashCode()

    override fun toString(): String = "SpiderTrace(operation=$operation, status=$status, startedAtMs=$startedAtMs, elapsedMs=$elapsedMs, request=$request, response=$response, errorCode=$errorCode, errorMessage=$errorMessage)"

}
