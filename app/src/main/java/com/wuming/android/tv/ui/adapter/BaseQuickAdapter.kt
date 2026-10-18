package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.databinding.AdapterQuickBinding

/** Quick-search results with platform-specific holder sizing hooks. */
@JvmSuppressWildcards
abstract class BaseQuickAdapter protected constructor(
    private val listener: OnClickListener,
) : BaseSelectAdapter<Vod, BaseQuickAdapter.ViewHolder>() {
    fun interface OnClickListener {
        fun onItemClick(item: Vod)
    }

    override fun addAll(items: List<Vod>) {
        val start = mItems.size
        mItems.addAll(items)
        notifyItemRangeInserted(start, items.size)
    }

    open fun remove(position: Int) {
        mItems.removeAt(position)
        notifyItemRemoved(position)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val holder = ViewHolder(AdapterQuickBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)
        configureHolder(holder)
        return holder
    }

    protected open fun configureHolder(holder: ViewHolder) {}

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = mItems[position]
        holder.binding.name.text = item.name
        holder.binding.site.text = item.getSiteName()
        holder.binding.remark.text = item.remarks
        holder.setItem(item)
    }

    open class ViewHolder(
        val binding: AdapterQuickBinding,
        listener: OnClickListener,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Vod? = null

        init {
            binding.root.setOnClickListener { item?.let(listener::onItemClick) }
        }

        open fun setItem(item: Vod?) {
            this.item = item
        }
    }
}
