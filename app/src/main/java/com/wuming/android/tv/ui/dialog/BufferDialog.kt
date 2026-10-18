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
import androidx.lifecycle.LifecycleObserver
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.DialogBufferBinding
import com.wuming.android.tv.impl.BufferCallback
import com.wuming.android.tv.setting.PlayerSettings
import com.wuming.android.tv.utils.DialogUtil
import com.wuming.android.tv.utils.KeyUtil
import com.wuming.android.tv.utils.TvAlertDialogBuilder

open class BufferDialog(
    private val context: Context,
    private val callback: BufferCallback,
) : LifecycleObserver {
    private var binding: DialogBufferBinding? = null
    private var dialog: AlertDialog? = null
    private var value = 0

    companion object {
        @JvmStatic fun create(fragment: Fragment): BufferDialog = BufferDialog(fragment.requireContext(), fragment as BufferCallback)

        @JvmStatic fun create(activity: FragmentActivity): BufferDialog = BufferDialog(activity, activity as BufferCallback)
    }

    open fun show() {
        binding = DialogBufferBinding.inflate(DialogUtil.inflater(context))
        initDialog()
    }

    private fun initDialog() {
        dialog =
            TvAlertDialogBuilder(context)
                .setTitle(R.string.player_buffer)
                .setView(binding!!.root)
                .setPositiveButton(R.string.dialog_positive, ::onPositive)
                .setNegativeButton(R.string.dialog_negative, ::onNegative)
                .setOnDismissListener { clear() }
                .create()
        dialog!!.window!!.setDimAmount(0f)
        dialog!!.show()
        value = PlayerSettings.getBuffer()
        binding!!.slider.value = value.toFloat()
        binding!!.slider.addOnChangeListener { _, value, fromUser -> if (fromUser) callback.setBuffer(value.toInt()) }
        binding!!.slider.setOnKeyListener { _, _, event ->
            val enter = KeyUtil.isEnterKey(event)
            if (enter) {
                callback.setBuffer(binding!!.slider.value.toInt())
                dialog!!.dismiss()
            }
            enter
        }
    }

    private fun onPositive(
        dialog: DialogInterface,
        which: Int,
    ) {
        callback.setBuffer(binding!!.slider.value.toInt())
        dialog.dismiss()
    }

    private fun onNegative(
        dialog: DialogInterface,
        which: Int,
    ) {
        callback.setBuffer(value)
        dialog.dismiss()
    }

    private fun clear() {
        binding?.slider?.clearOnChangeListeners()
        binding?.slider?.setOnKeyListener(null)
        binding = null
        dialog = null
    }
}
