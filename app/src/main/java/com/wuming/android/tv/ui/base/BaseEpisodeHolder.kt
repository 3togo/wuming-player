package com.wuming.android.tv.ui.base

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Episode

abstract class BaseEpisodeHolder(
    itemView: View,
) : RecyclerView.ViewHolder(itemView) {
    abstract fun initView(item: Episode)
}
