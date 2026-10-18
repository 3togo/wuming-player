package com.wuming.android.tv.player.exo

/** Record/API compatibility for Android playback callers; evidence is shared. */
internal class PlaybackHealthMonitor {
    private val shared = com.wuming.tv.core.playback.PlaybackHealthMonitor()
    fun record(nowMillis: Long, downloadMarginPermille: Int, lastRebufferRealtimeMs: Long) =
        shared.record(nowMillis, downloadMarginPermille, lastRebufferRealtimeMs)
    fun snapshot(nowMillis: Long): Snapshot = shared.snapshot(nowMillis).let {
        if (it === com.wuming.tv.core.playback.PlaybackHealthMonitor.Snapshot.UNKNOWN) Snapshot.UNKNOWN
        else Snapshot(it.marginPermille, it.rebufferId)
    }
    fun clear() = shared.clear()
    @JvmRecord data class Snapshot(val marginPermille: Int, val rebufferId: Long) {
        companion object { @JvmField val UNKNOWN = Snapshot(0, 0) }
    }
    companion object { @JvmField val FRESH_MILLIS = com.wuming.tv.core.playback.PlaybackHealthMonitor.FRESH_MILLIS }
}
