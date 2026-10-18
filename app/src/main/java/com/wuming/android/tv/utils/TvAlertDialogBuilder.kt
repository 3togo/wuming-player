package com.wuming.android.tv.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import com.wuming.android.tv.R
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/** Installs TV focus handling without replacing callers' show/dismiss listeners. */
class TvAlertDialogBuilder : MaterialAlertDialogBuilder {
    constructor(context: Context?) : super(context ?: throw NullPointerException("Context is required"))
    constructor(context: Context?, theme: Int) : super(context ?: throw NullPointerException("Context is required"), theme)

    override fun create(): AlertDialog {
        val colors = context.obtainStyledAttributes(intArrayOf(R.attr.dialogSurface))
        val surface = colors.getColor(0, Color.TRANSPARENT)
        colors.recycle()
        setBackground(
            MaterialShapeDrawable(
                ShapeAppearanceModel.builder()
                    .setAllCornerSizes(ControlSpacing.dimensionPixels(context, R.dimen.ds_corner_dialog).toFloat())
                    .build(),
            ).apply { fillColor = ColorStateList.valueOf(surface) },
        )
        val dialog = super.create()
        dialog.window?.decorView?.let(TextScale::applyDialog)
        if (Util.isLeanback() && dialog.window != null) TvDialogFocus.install(dialog.window!!.decorView)
        return dialog
    }
}
