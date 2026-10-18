package com.wuming.android.tv.ui.activity

import android.view.View
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.History
import com.wuming.android.tv.databinding.ActivityVideoBinding
import com.wuming.android.tv.player.PlayerManager
import com.wuming.android.tv.setting.PlayerSettings
import com.wuming.android.tv.utils.ResUtil

/** Playback-rate behavior shared by phone, TV and TV Lite controls. */
abstract class BaseSpeedScaleController protected constructor(
    private val host: Host,
    private val binding: ActivityVideoBinding,
) {
    interface Host {
        fun getPlayerManager(): PlayerManager
        fun getHistory(): History?
    }

    protected open fun onInteraction() {}

    private fun rememberSpeed() {
        val history = host.getHistory()
        if (history != null) history.speed = host.getPlayerManager().getSpeed()
        onInteraction()
    }

    fun onSpeed() {
        binding.control.action.speed.text = host.getPlayerManager().addSpeed()
        rememberSpeed()
    }

    fun onSpeedAdd() {
        binding.control.action.speed.text = host.getPlayerManager().addSpeed(0.25f)
        rememberSpeed()
    }

    fun onSpeedSub() {
        binding.control.action.speed.text = host.getPlayerManager().subSpeed(0.25f)
        rememberSpeed()
    }

    fun onSpeedLong(): Boolean {
        binding.control.action.speed.text = host.getPlayerManager().toggleSpeed()
        rememberSpeed()
        return true
    }

    fun onSpeedUp() {
        if (!host.getPlayerManager().isPlaying()) return
        binding.widget.overlaySpeed.visibility = View.VISIBLE
        binding.widget.overlaySpeed.startAnimation(ResUtil.getAnim(R.anim.forward))
        binding.control.action.speed.text = host.getPlayerManager().setSpeed(PlayerSettings.getSpeed())
    }

    fun onSpeedEnd() {
        binding.widget.overlaySpeed.clearAnimation()
        binding.widget.overlaySpeed.visibility = View.GONE
        val history = host.getHistory()
        if (history != null) {
            binding.control.action.speed.text = host.getPlayerManager().setSpeed(history.speed)
        }
    }
}
