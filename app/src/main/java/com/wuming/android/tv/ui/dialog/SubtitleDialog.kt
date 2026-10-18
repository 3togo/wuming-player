package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.media3.ui.SubtitleView
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.DialogSubtitleBinding
import com.wuming.android.tv.setting.PlayerSettings
import com.wuming.android.tv.utils.DialogUtil
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import com.github.bassaer.library.MDColor

class SubtitleDialog : BaseDialog() {
    private var binding: DialogSubtitleBinding? = null
    private var subtitleView: SubtitleView? = null
    private var full = false

    companion object {
        @JvmStatic fun create(): SubtitleDialog = SubtitleDialog()
    }

    fun view(subtitleView: SubtitleView?): SubtitleDialog {
        this.subtitleView = subtitleView
        return this
    }

    fun full(full: Boolean): SubtitleDialog {
        this.full = full
        return this
    }

    fun show(activity: FragmentActivity) {
        if (isDialogShowing(activity)) return
        show(activity.supportFragmentManager, null)
    }

    override fun transparent(): Boolean = full

    override fun getBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): ViewBinding =
        DialogSubtitleBinding.inflate(inflater, container, false).also {
            binding =
                it
        }

    override fun releaseBinding() {
        binding = null
        subtitleView = null
    }

    override fun initView() {
        // Android may restore the fragment without the original player view.
        if (subtitleView == null) {
            dismissAllowingStateLoss()
            return
        }
        val root = binding!!.root
        if (full) for (i in 0 until root.childCount) (root.getChildAt(i) as ImageView).drawable.setTint(MDColor.WHITE)
    }

    override fun initEvent() {
        binding!!.reset.setOnClickListener { onReset() }
    }

    private fun onReset() {
        val subtitle = subtitleView ?: return
        PlayerSettings.putSubtitleTextSize(0f)
        PlayerSettings.putSubtitlePosition(0f)
        subtitle.setUserDefaultTextSize()
    }

    override fun onResume() {
        super.onResume()
        if (full) setDimAmount(0.5f)
        dialog?.window?.setLayout(ResUtil.getDimensionPixelSize(if (full) R.dimen.vs_232 else R.dimen.vs_216), -1)
    }
}
