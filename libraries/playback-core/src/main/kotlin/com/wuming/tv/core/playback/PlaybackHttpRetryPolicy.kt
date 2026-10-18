package com.wuming.tv.core.playback

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Applies to the current failed request; never blacklists a source or a host. */
object PlaybackHttpRetryPolicy {
    @JvmStatic fun shouldRetry(status: Int): Boolean = status == 408 || status == 429 || status in 500..599

    /** Loader attempts are 1..3; a source restart uses 4..5. */
    @JvmStatic fun retryDelayMillis(status: Int, attempt: Int, retryAfter: String?, nowEpochMs: Long): Long? {
        if (!shouldRetry(status) || attempt !in 1..5) return null
        val backoff = 1_000L shl (attempt - 1)
        val serverDelay = retryAfterMillis(retryAfter, nowEpochMs) ?: 0L
        // A longer server throttle cannot be honored by this bounded recovery path.
        if (serverDelay > MAX_DELAY_MS) return null
        return maxOf(backoff, serverDelay)
    }

    @JvmStatic fun retryAfterMillis(value: String?, nowEpochMs: Long): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seconds = text.toLongOrNull()
        if (seconds != null) {
            if (seconds < 0L) return null
            return if (seconds > MAX_DELAY_MS / 1_000L) MAX_DELAY_MS + 1L else seconds * 1_000L
        }
        return try {
            val target = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (target - nowEpochMs).coerceAtLeast(0L)
        } catch (_: Exception) {
            null
        }
    }

    private const val MAX_DELAY_MS = 60_000L
}
