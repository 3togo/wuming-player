package com.wuming.android.tv.ui.base

import android.widget.TextView
import com.wuming.android.tv.utils.Clock

class LivePlaybackResources(
    owner: Any?,
    clockView: TextView?,
    videoClockView: TextView?,
) {
    private val playerHandler = LivePlayerHandler(owner)
    private val clock = if (clockView == null) null else Clock.create(clockView)
    private val videoClock = Clock.create(videoClockView).format("HH:mm")

    fun playerHandler(): LivePlayerHandler = playerHandler

    fun clock(): Clock? = clock

    fun videoClock(): Clock = videoClock

    fun release() {
        clock?.release()
        videoClock.release()
        playerHandler.release()
    }
}
