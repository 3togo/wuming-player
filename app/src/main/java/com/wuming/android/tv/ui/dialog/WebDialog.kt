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
import com.wuming.android.tv.utils.DialogUtil
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.TvAlertDialogBuilder

open class WebDialog(
    activity: Activity,
    view: View,
) {
    private val dialog = TvAlertDialogBuilder(activity).setView(view).create()

    init {
        dialog.setOnDismissListener(view as DialogInterface.OnDismissListener)
    }

    companion object {
        @JvmStatic fun create(
            activity: Activity,
            view: View,
        ): WebDialog = WebDialog(activity, view)
    }

    open fun show(): WebDialog {
        initDialog()
        return this
    }

    open fun dismiss() {
        dialog.setOnDismissListener(null)
        dialog.dismiss()
    }

    private fun initDialog() {
        dialog.show()
        val window = dialog.window ?: return
        val params = window.attributes
        params.height = LayoutConfig.getScreenHeightPixels(LayoutConfig.WEB_DIALOG_HEIGHT_RATIO)
        params.width = LayoutConfig.getScreenWidthPixels(LayoutConfig.WEB_DIALOG_WIDTH_RATIO)
        window.attributes = params
        window.setDimAmount(0f)
    }
}
