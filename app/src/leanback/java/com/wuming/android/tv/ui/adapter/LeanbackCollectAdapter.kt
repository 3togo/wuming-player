package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Collect
import com.wuming.android.tv.databinding.AdapterFilterBinding
import com.wuming.android.tv.utils.LayoutConfig

open class LeanbackCollectAdapter(
    private val listener: OnClickListener,
) : BaseSelectAdapter<Collect, LeanbackCollectAdapter.ViewHolder>() {
    fun interface OnClickListener {
        fun onItemClick(
            position: Int,
            item: Collect,
        )
    }

    open fun add(item: Collect) {
        mItems.add(item)
        notifyItemInserted(mItems.size - 1)
    }

    open fun add(
        position: Int,
        item: Collect,
    ) {
        mItems.add(position, item)
        notifyItemInserted(position)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val holder = ViewHolder(AdapterFilterBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)
        LayoutConfig.styleTvSearchFilterChip(holder.binding.text, LayoutConfig.getFastSearchSiteItemHeightPixels())
        return holder
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = mItems[position]
        holder.binding.text.text = item.getSite().name

        holder.setItem(item)
    }

    open class ViewHolder(
        val binding: AdapterFilterBinding,
        listener: OnClickListener,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Collect? = null
        private var position = 0

        init {
            binding.root.setOnClickListener { item?.let { listener.onItemClick(bindingAdapterPosition, it) } }
        }

        open fun setItem(item: Collect?) {
            this.item = item
        }
    }
}
