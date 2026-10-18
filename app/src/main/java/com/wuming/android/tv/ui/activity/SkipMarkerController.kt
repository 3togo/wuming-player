package com.wuming.android.tv.ui.activity

import com.wuming.android.tv.bean.History
import com.wuming.android.tv.player.PlayerManager

/** Shared intro/outro marker actions; hosts own persistence and input-specific feedback. */
class SkipMarkerController @JvmOverloads constructor(
    private val host: Host,
    private val onInteraction: Runnable = Runnable {},
) {
    interface Host {
        fun getPlayerManager(): PlayerManager
        fun getHistory(): History?
        fun setOpening(opening: Long)
        fun setEnding(ending: Long)
    }

    fun onOpening() {
        val player = host.getPlayerManager()
        val position = player.getPosition()
        val duration = player.getDuration()
        if (player.canSetOpening(position, duration)) host.setOpening(position)
        onInteraction.run()
    }

    fun onOpeningAdd() {
        val history = host.getHistory() ?: return
        host.setOpening((history.opening.coerceAtLeast(0L) + 1000L).coerceAtLeast(0L))
    }

    fun onOpeningSub() {
        val history = host.getHistory() ?: return
        host.setOpening((history.opening.coerceAtLeast(0L) - 1000L).coerceAtLeast(0L))
    }

    fun onOpeningReset(): Boolean {
        onInteraction.run()
        host.setOpening(0)
        return true
    }

    fun onEnding() {
        val player = host.getPlayerManager()
        val position = player.getPosition()
        val duration = player.getDuration()
        if (player.canSetEnding(position, duration)) host.setEnding(duration - position)
        onInteraction.run()
    }

    fun onEndingAdd() {
        val history = host.getHistory() ?: return
        host.setEnding((history.ending.coerceAtLeast(0L) + 1000L).coerceAtLeast(0L))
    }

    fun onEndingSub() {
        val history = host.getHistory() ?: return
        host.setEnding((history.ending.coerceAtLeast(0L) - 1000L).coerceAtLeast(0L))
    }

    fun onEndingReset(): Boolean {
        onInteraction.run()
        host.setEnding(0)
        return true
    }
}
