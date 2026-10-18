package com.wuming.android.tv.ui.adapter

import com.wuming.android.tv.utils.TextScale

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.ResUtil

open class LeanbackFastListAdapter : BaseFastListAdapter() {
    private var targetItemHeight = 0

    open fun setTargetItemHeight(height: Int) {
        targetItemHeight = height
    }

    override fun configureHolder(
        holder: ViewHolder,
        parent: ViewGroup,
    ) {
        if (siteListLayout == 1 || siteListLayout == 2) {
            LayoutConfig.styleTvSearchFilterChip(holder.tvSearchSite, targetItemHeight)
        } else {
            TextScale.bind(holder.tvSearchSite)
        }
    }
}
