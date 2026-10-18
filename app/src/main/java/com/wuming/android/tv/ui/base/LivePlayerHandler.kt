package com.wuming.android.tv.ui.base

import com.wuming.android.tv.player.PlayerManager

/** Live playback speed and decoder operations. */
open class LivePlayerHandler(
    activity: Any?,
) {
    private var player: PlayerManager? = null
    private val speedSteps = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    private var currentSpeedIndex = 2

    open fun setPlayer(player: PlayerManager?) {
        this.player = player
    }

    open fun handleSpeedChange(): String {
        val player = player ?: return ""
        currentSpeedIndex = (currentSpeedIndex + 1) % speedSteps.size
        player.setSpeed(speedSteps[currentSpeedIndex])
        return player.getSpeedText()
    }

    open fun toggleSpeed(): String {
        val player = player ?: return ""
        player.setSpeed(if (player.getSpeed() < 1.5f) 2.0f else 1.0f)
        return player.getSpeedText()
    }

    open fun handleDecodeChange() {
        player?.toggleDecode()
    }

    open fun release() {
        player = null
    }
}
