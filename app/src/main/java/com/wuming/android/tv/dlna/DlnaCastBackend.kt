package com.wuming.android.tv.dlna

import android.content.Context
import com.wuming.android.tv.api.projection.CastBackend
import com.wuming.android.tv.bean.CastVideo
import com.wuming.android.tv.bean.Device

/** jUPnP-backed implementation of the protocol-neutral casting boundary. */
class DlnaCastBackend private constructor(
    private val manager: DLNACastManager,
) : CastBackend {

    constructor() : this(DLNACastManager.get())

    private var deviceListener: DLNACastManager.DeviceListener? = null

    override fun startDiscovery(context: Context, listener: CastBackend.DiscoveryListener) {
        deviceListener = object : DLNACastManager.DeviceListener {
            override fun onDeviceAdded(device: Device) {
                listener.onDeviceAdded(device)
            }

            override fun onDeviceRemoved(device: Device) {
                listener.onDeviceRemoved(device)
            }
        }
        manager.setDeviceListener(deviceListener)
        manager.init(context)
        manager.getRegistered().forEach(listener::onDeviceAdded)
        manager.search()
    }

    override fun stopDiscovery() {
        manager.setDeviceListener(null)
        deviceListener = null
    }

    override fun createSession(
        video: CastVideo,
        onPlaying: Runnable,
        onEnded: Runnable,
        onFailed: Runnable,
    ): CastBackend.Session = DlnaSession(DLNACast(video, onPlaying, onEnded, onFailed))

    private class DlnaSession(
        private val cast: DLNACast,
    ) : CastBackend.Session {
        override fun start(device: Device) = cast.cast(device)

        override fun stop() = cast.stop()

        override fun seek(positionMs: Long) = cast.seek(positionMs)

        override fun getCurrentPositionMs(): Long = cast.currentPositionMs

        override fun release() = cast.release()
    }
}
