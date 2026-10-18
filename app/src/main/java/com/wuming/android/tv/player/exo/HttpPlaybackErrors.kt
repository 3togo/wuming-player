package com.wuming.android.tv.player.exo

import androidx.media3.datasource.HttpDataSource
import com.wuming.tv.core.playback.PlaybackHttpRetryPolicy

/** A denied/expired current request needs another route or user action, not the same request loop. */
object HttpPlaybackErrors {
    @JvmStatic fun shouldRetry(error: Throwable): Boolean {
        return responseCode(error)?.let { PlaybackHttpRetryPolicy.shouldRetry(it) } ?: true
    }

    @JvmStatic fun responseCode(error: Throwable): Int? {
        return response(error)?.responseCode
    }

    @JvmStatic fun retryAfter(error: Throwable): String? {
        val headers = response(error)?.headerFields ?: return null
        return headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.firstOrNull()
    }

    private fun response(error: Throwable): HttpDataSource.InvalidResponseCodeException? {
        var cause: Throwable? = error
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while (cause != null && seen.add(cause)) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause
            }
            cause = cause.cause
        }
        return null
    }
}
