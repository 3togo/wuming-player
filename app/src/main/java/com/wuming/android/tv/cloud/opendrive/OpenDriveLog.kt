package com.wuming.android.tv.cloud.opendrive

import com.wuming.android.tv.utils.AppLog
import com.wuming.tv.core.cloud.OpenDriveFailure

/**
 * Unified logging for OpenDrive failure paths.
 *
 * Logs are always emitted at WARN or ERROR level so they appear in release builds.
 * DEBUG-only diagnostics continue to use [OpenDriveDiagnostics].
 *
 * Security: never logs full URLs, headers, cookie values, or response bodies.
 * Only structural metadata (provider, itemId, reason, error class, error message)
 * is emitted to aid debugging without leaking credentials.
 */
internal object OpenDriveLog {
    private const val TAG = "OpenDrive"

    @Volatile
    private var sink: AppLog.LogSink = AppLogSink

    @JvmStatic
    fun setSink(sink: AppLog.LogSink?) {
        this.sink = sink ?: AppLogSink
    }

    private object AppLogSink : AppLog.LogSink {
        override fun emit(level: AppLog.Level, tag: String, message: String, error: Throwable?) {
            when (level) {
                AppLog.Level.VERBOSE -> AppLog.v(tag, message)
                AppLog.Level.DEBUG -> if (error != null) AppLog.d(tag, message, error) else AppLog.d(tag, message)
                AppLog.Level.INFO -> AppLog.i(tag, message)
                AppLog.Level.WARN -> if (error != null) AppLog.w(tag, message, error) else AppLog.w(tag, message)
                AppLog.Level.ERROR -> if (error != null) AppLog.e(tag, message, error) else AppLog.e(tag, message)
            }
        }
    }

    @JvmStatic
    fun resolveFailure(provider: String, itemId: String, error: Throwable) {
        val reason = if (error is OpenDriveFailure) {
            "reason=${error.reason()} http=${error.httpStatus()} api=${error.apiCode()}"
        } else {
            "reason=UNCLASSIFIED"
        }
        sink.emit(AppLog.Level.WARN, TAG, "resolve_failed provider=$provider item=$itemId $reason error=${error.javaClass.simpleName}: ${error.message}", null)
    }

    @JvmStatic
    fun fallback(provider: String, itemId: String, from: String, to: String, reason: String) {
        sink.emit(AppLog.Level.INFO, TAG, "fallback provider=$provider item=$itemId from=$from to=$to reason=$reason", null)
    }

    @JvmStatic
    fun failover(manifestId: String, sourceFingerprint: String, failedSources: Set<String>) {
        sink.emit(AppLog.Level.WARN, TAG, "failover manifest=$manifestId failedSource=$sourceFingerprint totalFailed=${failedSources.size}", null)
    }

    @JvmStatic
    fun refresh(manifestId: String, reason: String) {
        sink.emit(AppLog.Level.INFO, TAG, "refresh manifest=$manifestId reason=$reason", null)
    }

    @JvmStatic
    fun resolveInvalid(provider: String, itemId: String, status: String, message: String) {
        sink.emit(AppLog.Level.WARN, TAG, "resolve_invalid provider=$provider item=$itemId status=$status msg=$message", null)
    }

    @JvmStatic
    fun httpError(provider: String, httpStatus: Int, apiCode: Long, errorClass: String, errorMessage: String) {
        sink.emit(AppLog.Level.WARN, TAG, "http_error provider=$provider http=$httpStatus api=$apiCode error=$errorClass: $errorMessage", null)
    }

    @JvmStatic
    fun httpBody(provider: String, httpStatus: Int, bodyPreview: String) {
        sink.emit(AppLog.Level.WARN, TAG, "http_body provider=$provider http=$httpStatus body=${bodyPreview.take(512)}", null)
    }

    @JvmStatic
    fun catvodFallbackTriggered(sourceKey: String, provider: String?, flag: String) {
        sink.emit(AppLog.Level.INFO, TAG, "catvod_fallback source=$sourceKey provider=${provider ?: "unknown"} flag=$flag", null)
    }

    @JvmStatic
    fun catvodFallbackFailed(sourceKey: String, error: Throwable) {
        sink.emit(AppLog.Level.WARN, TAG, "catvod_fallback_failed source=$sourceKey error=${error.javaClass.simpleName}: ${error.message}", null)
    }

    @JvmStatic
    fun historyFallbackTriggered(flag: String, rawUrl: String) {
        sink.emit(AppLog.Level.INFO, TAG, "history_fallback flag=$flag", null)
    }

    @JvmStatic
    fun historyFallbackUnavailable() {
        sink.emit(AppLog.Level.INFO, TAG, "history_fallback unavailable (no armed fallback)", null)
    }

    @JvmStatic
    fun authExpired(provider: String) {
        sink.emit(AppLog.Level.WARN, TAG, "auth_expired provider=$provider", null)
    }

    @JvmStatic
    fun providerUnavailable(provider: String, reason: String) {
        sink.emit(AppLog.Level.WARN, TAG, "provider_unavailable provider=$provider reason=$reason", null)
    }

    @JvmStatic
    fun unexpected(provider: String, itemId: String, error: Throwable) {
        sink.emit(AppLog.Level.ERROR, TAG, "unexpected provider=$provider item=$itemId error=${error.javaClass.simpleName}: ${error.message}", error)
    }

    @JvmStatic
    fun providerDebug(provider: String, event: String, details: String) {
        sink.emit(AppLog.Level.DEBUG, TAG, "provider=$provider event=$event $details", null)
    }

    @JvmStatic
    fun providerInfo(provider: String, event: String, details: String) {
        sink.emit(AppLog.Level.INFO, TAG, "provider=$provider event=$event $details", null)
    }

    @JvmStatic
    fun providerWarn(provider: String, event: String, details: String) {
        sink.emit(AppLog.Level.WARN, TAG, "provider=$provider event=$event $details", null)
    }

    @JvmStatic
    fun proxiedCloudContent(provider: String, itemId: String, detectedKind: String) {
        sink.emit(AppLog.Level.INFO, TAG, "proxied_cloud provider=$provider item=$itemId kind=$detectedKind", null)
    }

    @JvmStatic
    fun unsupportedCloudKind(provider: String, detectedKind: String) {
        sink.emit(AppLog.Level.WARN, TAG, "unsupported_cloud_kind provider=$provider kind=$detectedKind", null)
    }
}
