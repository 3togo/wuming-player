package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.DialogUaBinding
import com.wuming.android.tv.impl.UaCallback
import com.wuming.android.tv.setting.PlayerSettings
import com.wuming.android.tv.ui.custom.CustomTextListener
import com.wuming.android.tv.utils.DialogUtil
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import com.github.catvod.utils.Util

open class UaDialog(
    private val context: Context,
    private val callback: UaCallback,
) {
    private val binding = DialogUaBinding.inflate(DialogUtil.inflater(context))
    private var dialog: AlertDialog? = null
    private var append = true
    private val textListener: CustomTextListener = UaTextListener(this)

    companion object {
        @JvmStatic fun create(fragment: Fragment): UaDialog = UaDialog(fragment.requireContext(), fragment as UaCallback)

        @JvmStatic fun create(activity: FragmentActivity): UaDialog = UaDialog(activity, activity as UaCallback)
    }

    open fun show() {
        initDialog()
        initView()
        initEvent()
    }

    private fun initDialog() {
        dialog =
            TvAlertDialogBuilder(binding.root.context)
                .setTitle(R.string.player_ua)
                .setView(binding.root)
                .setPositiveButton(R.string.dialog_positive, ::onPositive)
                .setNegativeButton(R.string.dialog_negative, ::onNegative)
                .setOnDismissListener { clear() }
                .create()
        dialog!!.window!!.setDimAmount(0f)
        dialog!!.show()
    }

    private fun initView() {
        val text = PlayerSettings.getUa()
        binding.text.setText(text)
        binding.text.setSelection(if (text.isNullOrEmpty()) 0 else text.length)
    }

    private fun initEvent() {
        binding.text.addTextChangedListener(textListener)
        binding.text.setOnEditorActionListener { _, action, _ ->
            // The editor action can arrive after dismissal cleared the dialog.
            if (action == EditorInfo.IME_ACTION_DONE) {
                dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.performClick()
            }
            true
        }
    }

    open fun detect(s: String) {
        if (append && "c".equals(s, true)) {
            append = false
            binding.text.setText(Util.CHROME)
        } else if (append && "o".equals(s, true)) {
            append = false
            binding.text.setText(Util.OKHTTP)
        } else if (s.length > 1) {
            append = false
        } else if (s.isEmpty()) {
            append = true
        }
    }

    private fun onPositive(
        dialog: DialogInterface,
        which: Int,
    ) {
        callback.setUa(
            binding.text.text
                .toString()
                .trim { it <= ' ' },
        )
        dialog.dismiss()
    }

    private fun onNegative(
        dialog: DialogInterface,
        which: Int,
    ) {
        dialog.dismiss()
    }

    private fun clear() {
        binding.text.removeTextChangedListener(textListener)
        binding.text.setOnEditorActionListener(null)
        dialog = null
    }

    private class UaTextListener(
        private val dialog: UaDialog,
    ) : CustomTextListener() {
        override fun onTextChanged(
            s: CharSequence?,
            start: Int,
            before: Int,
            count: Int,
        ) {
            dialog.detect(s.toString())
        }
    }
}
