package com.wuming.android.tv.utils

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.wuming.android.tv.R

/** Shared Android rendering of design-core control metrics, independent of AutoSize. */
object ControlSpacing {
    @JvmStatic
    fun dimensionPixels(context: Context, dimension: Int): Int {
        val resources = context.resources
        val nativeDensity = resources.configuration.densityDpi / 160f
        return Math.round(resources.getDimension(dimension) / resources.displayMetrics.density * nativeDensity)
    }

    /** Called by the common typography walk, including newly attached dialog/recycler views. */
    fun apply(view: View) {
        val params = view.layoutParams ?: return
        val row = view.tag == "settings-row"
        val icon = view.tag == "control-icon"
        val action = view.tag == "app-action"
        val group = view.tag == "control-group"
        val dialogAction = view.id == android.R.id.button1 || view.id == android.R.id.button2 || view.id == android.R.id.button3
        val standardButton = view is Button && view !is CompoundButton && params.height == ViewGroup.LayoutParams.WRAP_CONTENT
        if (!row && !icon && !action && !group && !standardButton && !dialogAction) return
        val density = view.resources.configuration.densityDpi
        if (view.getTag(R.id.tag_control_spacing_applied) == density) return
        view.setTag(R.id.tag_control_spacing_applied, density)
        val height = dimensionPixels(view.context, R.dimen.ds_control_min_height)
        val horizontal = dimensionPixels(view.context, R.dimen.ds_control_padding_horizontal)
        val vertical = dimensionPixels(view.context, R.dimen.ds_control_padding_vertical)
        val gap = dimensionPixels(view.context, R.dimen.ds_control_gap)
        if (!group) {
            view.minimumHeight = height
            if (view is TextView) view.minHeight = height
            view.setPadding(if (icon) vertical else horizontal, vertical, if (icon) vertical else horizontal, vertical)
        }
        if (icon) {
            params.width = height
            params.height = height
            view.layoutParams = params
        }
        if (view is MaterialButton) {
            view.insetTop = 0
            view.insetBottom = 0
        }
        (params as? ViewGroup.MarginLayoutParams)?.let {
            if (it.topMargin > 0 || dialogAction) it.topMargin = if (dialogAction) 0 else gap
            if (it.bottomMargin > 0 || dialogAction) it.bottomMargin = if (dialogAction) 0 else gap
            view.layoutParams = it
        }
        if (dialogAction) (view.parent as? ViewGroup)?.let {
            it.minimumHeight = 0
            it.setPadding(it.paddingLeft, vertical, it.paddingRight, vertical)
        }
    }
}
