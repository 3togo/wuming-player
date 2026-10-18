package com.wuming.android.tv.ui.custom

import com.wuming.android.tv.utils.ControlSpacing
import android.graphics.Rect
import android.content.Context
import android.view.View
import androidx.annotation.DimenRes
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.DialogUtil

open class SpaceItemDecoration @JvmOverloads constructor(
    private val spanCount: Int,
    @DimenRes spacingRes: Int,
    context: Context? = null,
) : RecyclerView.ItemDecoration() {
    private val spacing = if (context == null) ResUtil.getDimensionPixelSize(spacingRes) else ControlSpacing.dimensionPixels(context, spacingRes)
    constructor(
        @DimenRes spacingRes: Int,
    ) : this(-1, spacingRes)

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position >= 0 && spanCount == -1) {
            outRect.left = if (position == 0) 0 else spacing / 2
            outRect.right = if (position == parent.adapter!!.itemCount - 1) 0 else spacing / 2
        } else if (position >= 0 && spanCount > 0) {
            val column = position % spanCount
            outRect.left = column * spacing / spanCount
            outRect.right = spacing - (column + 1) * spacing / spanCount
            if (position >= spanCount) outRect.top = spacing
        } else {
            outRect.set(0, 0, 0, 0)
        }
    }
}
