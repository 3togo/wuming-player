package com.wuming.android.tv.ui.adapter

import com.wuming.android.tv.utils.TextScale

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.utils.LayoutConfig

open class MobileFastListAdapter : BaseFastListAdapter() {
    override fun setSiteListLayout(layout: Int) {
        if (siteListLayout == layout) return
        super.setSiteListLayout(layout)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = siteListLayout

    override fun configureHolder(
        holder: ViewHolder,
        parent: ViewGroup,
    ) {
        val params = holder.itemView.layoutParams ?: return
        params.height = LayoutConfig.getMobileSearchSiteItemHeightPixels(parent.context)
        holder.itemView.layoutParams = params
    }

    override fun configureBoundHolder(holder: ViewHolder) {
        // Uniform size for every site rail row. Per-label fitting made the rail ragged
        // (short "显示全部" vs. ranked "站点名 (95%)"), and moved rows were never rebound.
        // The item's marquee ellipsize handles long names instead.
        TextScale.bind(holder.tvSearchSite)
    }
}
