package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.content.DialogInterface
import android.widget.AdapterView
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.TvAlertDialogBuilder

/** Action list with a TV remote focus indicator and initial selection. */
object LeanbackActionDialog {
    @JvmStatic fun show(
        activity: Activity,
        title: CharSequence?,
        items: Int,
        listener: DialogInterface.OnClickListener?,
    ) {
        val dialog = TvAlertDialogBuilder(activity).setTitle(title).setItems(items, listener).create()
        dialog.setOnShowListener {
            val list = dialog.listView ?: return@setOnShowListener
            list.setSelector(R.drawable.selector_video_rank_dialog_item)
            list.setDrawSelectorOnTop(false)
            list.isVerticalScrollBarEnabled = false
            list.isFocusable = true
            list.isFocusableInTouchMode = true
            if (list.count > 0 && list.selectedItemPosition == AdapterView.INVALID_POSITION) list.setSelection(0)
            list.requestFocus()
        }
        dialog.show()
    }
}
