package com.wuming.android.tv.ui.base

import android.view.View
import androidx.leanback.widget.Presenter
import com.wuming.android.tv.bean.Vod

abstract class BaseVodHolder(
    itemView: View,
) : Presenter.ViewHolder(itemView) {
    abstract fun initView(item: Vod)

    abstract fun unbind()
}
