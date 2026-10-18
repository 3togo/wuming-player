package com.wuming.android.tv.ui.activity

import android.view.View
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.ActivityVideoBinding
import com.wuming.android.tv.player.PlayerManager
import com.wuming.android.tv.service.PlaybackService
import com.wuming.android.tv.setting.PlayerSettings

open class DanmakuController(
    private val mHost: Host,
    private val mBinding: ActivityVideoBinding,
) {
    interface Host {
        fun getPlayerManager(): PlayerManager

        fun isFullscreenMode(): Boolean

        fun getService(): PlaybackService?
    }

    open fun onDanmakuShow() {
        PlayerSettings.putDanmakuShow(!PlayerSettings.isDanmakuShow())
        checkImg()
        show()
    }

    open fun setSize() {
        if (mHost.getService() == null) return
        mHost.getPlayerManager().setDanmakuSize(if (mHost.isFullscreenMode()) 1.0f else 0.8f)
    }

    open fun show() {
        mBinding.danmaku.visibility = if (PlayerSettings.isDanmakuShow()) View.VISIBLE else View.INVISIBLE
    }

    open fun hide() {
        mBinding.danmaku.visibility = View.INVISIBLE
    }

    open fun checkImg() {
        mBinding.control.danmakuToggle.setImageResource(
            if (PlayerSettings.isDanmakuShow()) R.drawable.ic_control_danmaku_on else R.drawable.ic_control_danmaku_off,
        )
    }
}
