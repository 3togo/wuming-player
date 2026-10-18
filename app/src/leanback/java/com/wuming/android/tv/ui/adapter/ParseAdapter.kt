package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Parse
import com.wuming.android.tv.databinding.AdapterParseBinding

open class ParseAdapter(
    listener: OnClickListener,
) : BaseParseAdapter<ParseAdapter.ViewHolder>(listener, false) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterParseBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = mItems[position]
        holder.binding.text.text = item.getName()
        holder.binding.text.isActivated = item.isActivated()
        holder.setItem(item)
    }

    open class ViewHolder(
        val binding: AdapterParseBinding,
        listener: OnClickListener,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Parse? = null

        init {
            binding.root.setOnClickListener { item?.let(listener::onItemClick) }
        }

        open fun setItem(item: Parse?) {
            this.item = item
        }
    }
}
