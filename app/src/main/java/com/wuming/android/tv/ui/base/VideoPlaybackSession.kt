package com.wuming.android.tv.ui.base

import com.wuming.android.tv.bean.History
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.recommendation.ViewingHistoryTracker
import com.wuming.tv.core.media.HistoryPlaybackFallback
import com.wuming.tv.core.playback.PlaybackSessionProgress

internal class VideoPlaybackSession {
    private val progress = PlaybackSessionProgress()
    private val fallback = HistoryPlaybackFallback()
    private val viewing = ViewingHistoryTracker()
    private var pendingFallbackHeaders: Map<String, String>? = null

    fun beginSelection() = progress.reset()

    fun armFallback(flag: String?, rawUrl: String?, headers: Map<String, String>? = null) =
        fallback.arm(flag, rawUrl, headers)

    fun consumeFallback(): HistoryPlaybackFallback.Attempt? = fallback.consume()

    fun clearFallback() = fallback.clear()

    fun updateFallbackHeaders(headers: Map<String, String>?) {
        fallback.updateHeaders(headers)
    }

    fun setPendingFallbackHeaders(headers: Map<String, String>?) {
        pendingFallbackHeaders = headers
    }

    fun consumePendingFallbackHeaders(): Map<String, String>? {
        val headers = pendingFallbackHeaders
        pendingFallbackHeaders = null
        return headers
    }

    fun finish(wallTime: Long, completed: Boolean) = viewing.finish(wallTime, completed)

    fun update(
        history: History,
        vod: Vod,
        playing: Boolean,
        position: Long,
        duration: Long,
        elapsedTime: Long,
        wallTime: Long
    ): PlaybackSessionProgress.Tick {
        history.createTime = wallTime
        history.position = position
        history.duration = duration
        viewing.update(history, vod, playing, position, duration, elapsedTime, wallTime)
        return progress.observe(position, duration, history.ending)
    }

    fun release(wallTime: Long) {
        viewing.finish(wallTime, false)
        fallback.clear()
        progress.reset()
    }
}
