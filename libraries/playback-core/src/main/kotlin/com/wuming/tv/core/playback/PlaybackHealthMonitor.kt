package com.wuming.tv.core.playback

import java.util.concurrent.TimeUnit

/** Recent playback evidence used to keep transport probes from worsening playback health. */
class PlaybackHealthMonitor {
    companion object {
        @JvmField val FRESH_MILLIS = TimeUnit.SECONDS.toMillis(10)
    }
    private val LOCK = Any()
    private var marginPermille = 0
    private var rebufferId = 0L
    private var updatedAtMillis = 0L

    fun record(
        nowMillis: Long,
        downloadMarginPermille: Int,
        lastRebufferRealtimeMs: Long,
    ) {
        synchronized(LOCK) {
            marginPermille = maxOf(0, minOf(4_000, downloadMarginPermille))
            if (lastRebufferRealtimeMs > 0) rebufferId = maxOf(rebufferId, lastRebufferRealtimeMs)
            updatedAtMillis = maxOf(0, nowMillis)
        }
    }

    fun snapshot(nowMillis: Long): Snapshot =
        synchronized(LOCK) {
            if (updatedAtMillis <= 0 || nowMillis < updatedAtMillis || nowMillis - updatedAtMillis > FRESH_MILLIS) {
                Snapshot.UNKNOWN
            } else {
                Snapshot(marginPermille, rebufferId)
            }
        }

    fun clear() {
        synchronized(LOCK) {
            marginPermille = 0
            rebufferId = 0
            updatedAtMillis = 0
        }
    }

    @JvmRecord data class Snapshot(
        val marginPermille: Int,
        val rebufferId: Long,
    ) {
        companion object {
            @JvmField val UNKNOWN = Snapshot(0, 0)
        }
    }
}
