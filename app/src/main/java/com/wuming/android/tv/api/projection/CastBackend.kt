package com.wuming.android.tv.api.projection

import android.content.Context
import com.wuming.android.tv.bean.CastVideo
import com.wuming.android.tv.bean.Device

/**
 * Protocol boundary for remote media playback.
 *
 * Media URL preparation, relay selection, queues, and user-visible session state remain owned
 * by ProjectionManager. Implementations own only device discovery and transport commands.
 */
interface CastBackend {
    fun startDiscovery(context: Context, listener: DiscoveryListener)
    fun stopDiscovery()
    fun createSession(video: CastVideo, onPlaying: Runnable, onEnded: Runnable, onFailed: Runnable): Session

    interface DiscoveryListener {
        fun onDeviceAdded(device: Device)
        fun onDeviceRemoved(device: Device)
    }

    interface Session {
        fun start(device: Device)
        fun stop()
        fun seek(positionMs: Long)
        fun getCurrentPositionMs(): Long
        fun release()
    }
}
