package com.wuming.android.tv.ui.adapter

import androidx.recyclerview.widget.DiffUtil
import com.wuming.android.tv.impl.Diffable

class BaseItemCallback<T : Diffable<T>> : DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(oldItem: T, newItem: T): Boolean = oldItem.isSameItem(newItem)

    override fun areContentsTheSame(oldItem: T, newItem: T): Boolean = oldItem.isSameContent(newItem)
}
