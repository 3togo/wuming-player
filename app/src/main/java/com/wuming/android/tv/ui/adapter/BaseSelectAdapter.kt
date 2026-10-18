package com.wuming.android.tv.ui.adapter

import androidx.recyclerview.widget.RecyclerView

/** Shared list management and single-selection behavior. */
@JvmSuppressWildcards
abstract class BaseSelectAdapter<T, VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {
    @JvmField protected val mItems: MutableList<T> = ArrayList()

    open fun addAll(items: List<T>) {
        mItems.clear()
        mItems.addAll(items)
        notifyDataSetChanged()
    }

    open fun clear() {
        mItems.clear()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = mItems.size

    open fun get(position: Int): T = mItems[position]

    open fun getItems(): MutableList<T> = mItems

    open fun indexOf(item: T): Int = mItems.indexOf(item)

    open fun isEmpty(): Boolean = mItems.isEmpty()

    open fun getPosition(): Int = mItems.indexOfFirst { isActivated(it) }.coerceAtLeast(0)

    protected open fun isActivated(item: T): Boolean = false

    open fun getActivated(): T? = if (mItems.isEmpty()) null else mItems[getPosition()]
}
