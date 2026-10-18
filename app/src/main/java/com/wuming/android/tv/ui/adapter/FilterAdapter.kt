package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Filter
import com.wuming.android.tv.databinding.AdapterFilterRowBinding
import com.wuming.android.tv.impl.FilterCallback
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.Util
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager

open class FilterAdapter(
    private val listener: FilterCallback,
    private val mItems: List<Filter>,
) : RecyclerView.Adapter<FilterAdapter.ViewHolder>() {
    override fun getItemCount(): Int = mItems.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterFilterRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = mItems[position]
        val tablet = !Util.isLeanback() && ResUtil.isPad()
        holder.binding.recycler.setHasFixedSize(!tablet)
        holder.binding.recycler.itemAnimator = null
        if (tablet && holder.binding.recycler.layoutManager !is FlexboxLayoutManager) {
            holder.binding.recycler.layoutManager = FlexboxLayoutManager(holder.itemView.context, FlexDirection.ROW).apply {
                flexWrap = FlexWrap.WRAP
            }
        }
        if (holder.binding.recycler.adapter == null) holder.binding.recycler.adapter = ValueAdapter(listener, item)
    }

    open class ViewHolder(
        val binding: AdapterFilterRowBinding,
    ) : RecyclerView.ViewHolder(binding.root)
}
