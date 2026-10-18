package com.wuming.android.tv.ui.base

/** Hold only playback-dependent selection restoration until the service connects. */
internal class VideoDetailLoadCoordinator<T> {
    private var playbackReady = false
    private var pending: T? = null

    fun onDetailReady(detail: T?): T? {
        if (playbackReady) return detail
        pending = detail
        return null
    }

    fun onPlaybackReady(): T? {
        playbackReady = true
        val detail = pending
        pending = null
        return detail
    }

    fun discardPending() {
        pending = null
    }
}
