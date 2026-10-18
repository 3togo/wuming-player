package com.wuming.android.tv.ui.adapter

import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.Parse

abstract class BaseParseAdapter<VH : RecyclerView.ViewHolder>(
    @JvmField protected val listener: OnClickListener,
    loadConfiguredParses: Boolean
) : BaseSelectAdapter<Parse, VH>() {

    init {
        if (loadConfiguredParses) mItems.addAll(VodConfig.get().parses)
    }

    fun first(): Parse = mItems[0]

    override fun isActivated(item: Parse): Boolean = item.isActivated()

    interface OnClickListener {
        fun onItemClick(item: Parse)
    }
}
