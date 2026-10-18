package com.wuming.tv.core.playback

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap

/** Bounds loader retries before the playback port displays a terminal error. */
object PlaybackLoadRetryPolicy {
    @JvmStatic fun retryDelayMillis(error: Throwable, errorCount: Int): Long? {
        val limit = if (isConnectionFailure(error)) 1 else 3
        if (errorCount !in 1..limit) return null
        return 1000L shl (errorCount - 1)
    }

    private fun isConnectionFailure(error: Throwable): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        while (cause != null && seen.add(cause)) {
            if (cause is SocketTimeoutException || cause is ConnectException ||
                cause is UnknownHostException || cause is NoRouteToHostException) return true
            cause = cause.cause
        }
        return false
    }
}
