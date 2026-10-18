package com.wuming.android.tv.ui.dialog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.DialogDanmakuSettingBinding
import com.wuming.android.tv.player.PlayerManager
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.Util

class DanmakuSettingDialog {
    private var player: PlayerManager? = null

    companion object {
        @JvmStatic fun create(): DanmakuSettingDialog = DanmakuSettingDialog()

        private fun inflate(
            inflater: LayoutInflater,
            container: ViewGroup?,
        ): DialogDanmakuSettingBinding = DialogDanmakuSettingBinding.inflate(inflater, container, false)
    }

    fun player(player: PlayerManager?): DanmakuSettingDialog {
        this.player = player
        return this
    }

    fun show(activity: FragmentActivity) {
        val manager = activity.supportFragmentManager
        if (manager.fragments.any { it is BottomSheet || it is SideSheet }) return
        if (ResUtil.isLand(activity) || Util.isFullscreen(activity)) {
            SideSheet(player).show(manager, null)
        } else {
            BottomSheet(player).show(manager, null)
        }
    }

    class BottomSheet
        @JvmOverloads
        constructor(
            private var player: PlayerManager? = null,
        ) : BaseDialog() {
            private var binding: DialogDanmakuSettingBinding? = null

            override fun getBinding(
                inflater: LayoutInflater,
                container: ViewGroup?,
            ): ViewBinding = inflate(inflater, container).also { binding = it }

            override fun releaseBinding() {
                binding = null
            }

            override fun initView() {
                val player = player
                if (player == null) {
                    dismissAllowingStateLoss()
                    return
                }
                DanmakuSettingPanel(binding!!, player).bind()
            }

            override fun onDestroyView() {
                player = null
                super.onDestroyView()
            }
        }

    class SideSheet
        @JvmOverloads
        constructor(
            private var player: PlayerManager? = null,
        ) : BaseSideSheetDialog() {
            private var binding: DialogDanmakuSettingBinding? = null

            override fun getWidth(): Int = minOf(ResUtil.getDimensionPixelSize(R.dimen.vs_420), ResUtil.getScreenWidth() / 2)

            override fun getBinding(
                inflater: LayoutInflater,
                container: ViewGroup?,
            ): ViewBinding =
                inflate(inflater, container).also {
                    binding = it
                }

            override fun releaseBinding() {
                binding = null
            }

            override fun initView() {
                val player = player
                if (player == null) {
                    dismissAllowingStateLoss()
                    return
                }
                DanmakuSettingPanel(binding!!, player).bind()
            }

            override fun onDestroyView() {
                player = null
                super.onDestroyView()
            }
        }
}
