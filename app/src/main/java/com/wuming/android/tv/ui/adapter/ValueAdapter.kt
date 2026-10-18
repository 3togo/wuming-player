package com.wuming.android.tv.ui.adapter

import com.wuming.android.tv.utils.TextScale

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Filter
import com.wuming.android.tv.bean.Value
import com.wuming.android.tv.databinding.AdapterValueBinding
import com.wuming.android.tv.impl.FilterCallback
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.Util

open class ValueAdapter(
    private val listener: FilterCallback,
    filter: Filter,
) : RecyclerView.Adapter<ValueAdapter.ViewHolder>() {
    private val items = filter.getValue()
    private val key = filter.getKey()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterValueBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.bind(items[position])
    }

    private fun onItemClick(value: Value) {
        for (item in items) item.setActivated(value)
        notifyItemRangeChanged(0, itemCount)
        listener.setFilter(key, value)
    }

    open class ViewHolder(
        private val binding: AdapterValueBinding,
        private val adapter: ValueAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Value? = null

        init {
            if (!Util.isLeanback() && ResUtil.isPad()) {
                TextScale.bind(binding.text)
                val gap = (4 * binding.root.resources.displayMetrics.density).toInt()
                (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    params.topMargin = gap
                    params.bottomMargin = gap
                    binding.root.layoutParams = params
                }
            }
            binding.text.setOnClickListener {
                val pos = bindingAdapterPosition
                val current = item
                if (pos != RecyclerView.NO_POSITION && current != null) adapter.onItemClick(current)
            }
        }

        open fun bind(item: Value) {
            this.item = item
            binding.text.text = item.getN()
            binding.text.isActivated = item.isActivated()
            TextScale.bind(binding.root)
        }
    }
}
