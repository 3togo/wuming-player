package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.wuming.android.tv.databinding.DialogContentBinding
import com.wuming.android.tv.ui.custom.CustomMovement
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import com.github.bassaer.library.MDColor

open class ContentDialog {
    companion object {
        @JvmStatic fun show(
            activity: Activity,
            content: CharSequence?,
        ) {
            ContentDialog().create(activity, content)
        }
    }

    open fun create(
        activity: Activity,
        content: CharSequence?,
    ) {
        val binding =
            DialogContentBinding.inflate(
                com.wuming.android.tv.utils.DialogUtil
                    .inflater(activity),
            )
        val dialog = TvAlertDialogBuilder(activity).setView(binding.root).create()
        dialog.window!!.setDimAmount(0f)
        initView(binding.text, content)
        dialog.show()
    }

    private fun initView(
        view: TextView,
        content: CharSequence?,
    ) {
        view.setText(content, TextView.BufferType.SPANNABLE)
        view.setLinkTextColor(MDColor.BLUE_500)
        CustomMovement.bind(view)
    }
}
