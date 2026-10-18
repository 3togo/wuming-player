package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.DialogSpeedBinding
import com.wuming.android.tv.impl.SpeedCallback
import com.wuming.android.tv.setting.PlayerSettings
import com.wuming.android.tv.utils.DialogUtil
import com.wuming.android.tv.utils.TvAlertDialogBuilder

open class SpeedDialog(
    private val context: Context,
    private val callback: SpeedCallback,
) {
    private val binding = DialogSpeedBinding.inflate(DialogUtil.inflater(context))
    private var dialog: AlertDialog? = null
    private var value = 0f

    companion object {
        @JvmStatic fun create(fragment: Fragment): SpeedDialog = SpeedDialog(fragment.requireContext(), fragment as SpeedCallback)

        @JvmStatic fun create(activity: FragmentActivity): SpeedDialog = SpeedDialog(activity, activity as SpeedCallback)
    }

    open fun show() {
        initDialog()
        initView()
    }

    private fun initDialog() {
        dialog =
            TvAlertDialogBuilder(binding.root.context)
                .setTitle(R.string.player_speed)
                .setView(binding.root)
                .setPositiveButton(R.string.dialog_positive, ::onPositive)
                .setNegativeButton(R.string.dialog_negative, ::onNegative)
                .setOnDismissListener { clear() }
                .create()
        dialog!!.window!!.setDimAmount(0f)
        dialog!!.show()
    }

    private fun initView() {
        value = PlayerSettings.getSpeed()
        binding.slider.value = value
    }

    private fun onPositive(
        dialog: DialogInterface,
        which: Int,
    ) {
        callback.setSpeed(binding.slider.value)
        dialog.dismiss()
    }

    private fun onNegative(
        dialog: DialogInterface,
        which: Int,
    ) {
        callback.setSpeed(value)
        dialog.dismiss()
    }

    private fun clear() {
        binding.slider.clearOnChangeListeners()
        dialog = null
    }
}
