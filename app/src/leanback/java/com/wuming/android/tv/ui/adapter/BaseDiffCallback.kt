package com.wuming.android.tv.ui.adapter

import androidx.leanback.widget.DiffCallback
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.impl.Diffable

open class BaseDiffCallback<T : Diffable<T>> : DiffCallback<T>() {
    override fun areItemsTheSame(
        oldItem: T,
        newItem: T,
    ): Boolean = oldItem.isSameItem(newItem)

    override fun areContentsTheSame(
        oldItem: T,
        newItem: T,
    ): Boolean = oldItem.isSameContent(newItem)
}
