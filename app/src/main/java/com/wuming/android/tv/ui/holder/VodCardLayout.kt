package com.wuming.android.tv.ui.holder

import android.view.View
import android.view.ViewGroup

/** Applies the existing width, optional card height, margin and image-height tuple. */
internal object VodCardLayout {
    fun apply(root: View, image: View, size: IntArray?) {
        if (size == null || size.isEmpty()) return
        val params =
            root.layoutParams as? ViewGroup.MarginLayoutParams
                ?: ViewGroup.MarginLayoutParams(size[0], if (size.size > 1) size[1] else ViewGroup.LayoutParams.WRAP_CONTENT)
        params.width = size[0]
        if (size.size > 1 && size[1] > 0) params.height = size[1]
        if (size.size > 2 && size[2] > 0) {
            val m = size[2]
            params.setMargins(m, m, m, m)
        }
        root.layoutParams = params
        if (size.size > 3 &&
            size[3] > 0
        ) {
            val imageParams = image.layoutParams
            imageParams.height = size[3]
            image.layoutParams =
                imageParams
        }
    }
}
