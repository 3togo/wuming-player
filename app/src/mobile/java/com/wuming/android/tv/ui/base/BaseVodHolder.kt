package com.wuming.android.tv.ui.base

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Vod

abstract class BaseVodHolder(
    itemView: View,
) : RecyclerView.ViewHolder(itemView) {
    abstract fun initView(item: Vod)

    abstract fun unbind()
}
