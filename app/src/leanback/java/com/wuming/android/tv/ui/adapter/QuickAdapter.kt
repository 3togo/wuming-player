package com.wuming.android.tv.ui.adapter

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.ResUtil

open class QuickAdapter(
    listener: OnClickListener,
) : BaseQuickAdapter(listener) {
    private val width: Int
    private var nextFocusUp = View.NO_ID

    init {
        val space =
            ResUtil.getDimensionPixelSize(R.dimen.vs_24) + ResUtil.getDimensionPixelSize(R.dimen.vs_32)
        width =
            (ResUtil.getScreenWidth() - space) / 4
    }

    override fun configureHolder(holder: ViewHolder) {
        holder.itemView.layoutParams.width = width
    }

    open fun setNextFocusUp(nextFocusUp: Int) {
        this.nextFocusUp = nextFocusUp
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        super.onBindViewHolder(holder, position)
        holder.itemView.nextFocusUpId =
            nextFocusUp
    }
}
