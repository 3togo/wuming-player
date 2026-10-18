package com.wuming.android.tv.ui.custom

import android.annotation.SuppressLint
import androidx.annotation.DimenRes
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.RowPresenter
import com.wuming.android.tv.utils.ResUtil

// This adapter deliberately uses Leanback's restricted item-scroll constant and setter.
// Class scope also covers Kotlin's generated default-argument constructor.
@SuppressLint("RestrictedApi")
open class CustomRowPresenter
    @JvmOverloads
    constructor(
        @DimenRes private val spacing: Int,
        focusZoomFactor: Int = FocusHighlight.ZOOM_FACTOR_SMALL,
        private val strategy: Int = HorizontalGridView.FOCUS_SCROLL_ITEM,
    ) : ListRowPresenter(focusZoomFactor) {
        init {
            setShadowEnabled(false)
            setSelectEffectEnabled(false)
            setKeepChildForeground(false)
        }

        @SuppressLint("RestrictedApi")
        override fun initializeRowViewHolder(holder: RowPresenter.ViewHolder) {
            super.initializeRowViewHolder(holder)
            val vh = holder as ListRowPresenter.ViewHolder
            vh.gridView.focusScrollStrategy = strategy
            vh.gridView.setHorizontalSpacing(ResUtil.getDimensionPixelSize(spacing))
        }
    }
