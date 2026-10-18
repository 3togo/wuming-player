package com.wuming.tv.core.cloud

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** Post-provider classification only. Never retries, changes routes, or clears credentials. */
object OpenDriveFailurePolicy {
    class Status(val http: Int = 0, val api: Long = 0, val quota: Boolean = false)
    fun interface StatusPort { fun read(error: Throwable): Status? }

    @JvmStatic
    fun describe(provider: String, error: Exception, statusPort: StatusPort): Exception {
        if (error is InterruptedException || error is CancellationException) return error
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        while (cause != null && seen.add(cause)) {
            when (cause) {
                is InterruptedException, is CancellationException -> return error
                is OpenDriveFailure -> return cause
            }
            val status = statusPort.read(cause)
            if (status?.quota == true) return OpenDriveFailure(provider, OpenDriveFailure.Reason.QUOTA_EXHAUSTED, 0, 0, error)
            if (status != null && status.http != 0) {
                val reason = when (status.http) {
                    401 -> OpenDriveFailure.Reason.AUTH_REQUIRED
                    403 -> OpenDriveFailure.Reason.ACCESS_DENIED
                    404, 410 -> OpenDriveFailure.Reason.RESOURCE_UNAVAILABLE
                    500, 502, 503, 504 -> OpenDriveFailure.Reason.NETWORK
                    else -> OpenDriveFailure.Reason.PROVIDER_ERROR
                }
                return OpenDriveFailure(provider, reason, status.http, status.api, error)
            }
            if (cause is SocketException || cause is UnknownHostException || cause is SocketTimeoutException) {
                return OpenDriveFailure(provider, OpenDriveFailure.Reason.NETWORK, 0, 0, error)
            }
            cause = cause.cause
        }
        if (error is InterruptedIOException) return error
        if (error is IOException) return OpenDriveFailure(provider, OpenDriveFailure.Reason.PROVIDER_ERROR, 0, 0, error)
        return error
    }
}
