package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.impl.Diffable
import java.util.stream.Collectors

@JvmSuppressWildcards
abstract class BaseDiffAdapter<T : Diffable<T>, VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {
    @JvmField protected val differ = AsyncListDiffer<T>(this, BaseItemCallback<T>())

    open fun getItem(position: Int): T = differ.currentList[position]

    open fun getItems(): MutableList<T> = differ.currentList

    open fun setItems(items: List<T>?) {
        setItems(items, Runnable {})
    }

    open fun setItems(
        items: List<T>?,
        runnable: Runnable?,
    ) {
        differ.submitList(items ?: ArrayList()) { runnable?.run() }
    }

    open fun setItems(
        items: List<T>?,
        callback: Callback,
    ) {
        differ.submitList(items ?: ArrayList()) { callback.onUpdateFinished(true) }
    }

    open fun add(item: T) {
        add(item, null)
    }

    open fun add(
        item: T,
        runnable: Runnable?,
    ) {
        val current = ArrayList(getItems())
        current.add(item)
        setItems(current, runnable)
    }

    open fun addAll(items: List<T>) {
        addAll(items, null)
    }

    open fun addAll(
        items: List<T>,
        runnable: Runnable?,
    ) {
        val current = ArrayList(getItems())
        current.addAll(items)
        setItems(current, runnable)
    }

    open fun sort(item: T) {
        sort(item, null)
    }

    open fun sort(
        item: T,
        runnable: Runnable?,
    ) {
        var current: MutableList<T> = ArrayList(getItems())
        if (!current.contains(item)) {
            current.add(item)
            current = current.stream().sorted().collect(Collectors.toList())
        }
        setItems(current, runnable)
    }

    open fun sort(items: List<T>) {
        sort(items, null)
    }

    open fun sort(
        items: List<T>,
        runnable: Runnable?,
    ) {
        var current: MutableList<T> = ArrayList(getItems())
        var changed = false
        for (item in items) {
            if (!current.contains(item)) {
                current.add(item)
                changed = true
            }
        }
        if (changed) current = current.stream().sorted().collect(Collectors.toList())
        setItems(current, runnable)
    }

    open fun remove(item: T) {
        remove(item, null)
    }

    open fun remove(
        item: T,
        runnable: Runnable?,
    ) {
        val current = ArrayList(getItems())
        if (current.remove(item)) setItems(current, runnable)
    }

    open fun clear() {
        clear(null)
    }

    open fun clear(runnable: Runnable?) {
        if (getItems().isEmpty()) {
            runnable?.run()
            return
        }
        setItems(ArrayList(), runnable)
    }

    override fun getItemCount(): Int = differ.currentList.size

    abstract override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): VH

    abstract override fun onBindViewHolder(
        holder: VH,
        position: Int,
    )

    fun interface Callback {
        fun onUpdateFinished(hasChange: Boolean)
    }
}
