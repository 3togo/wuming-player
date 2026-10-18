package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Class
import com.wuming.android.tv.bean.FilterCache
import com.wuming.android.tv.databinding.AdapterTypeBinding
import com.wuming.android.tv.utils.ResUtil

open class LeanbackTypeAdapter(
    private val listener: OnClickListener,
) : BaseSelectAdapter<Class, LeanbackTypeAdapter.ViewHolder>() {
    interface OnClickListener {
        fun onItemClick(item: Class)

        fun onRefresh(item: Class)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterTypeBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = mItems[position]
        holder.binding.text.text = item.getTypeName()
        holder.binding.text.compoundDrawablePadding = ResUtil.getDimensionPixelSize(R.dimen.vs_4)
        holder.binding.text.setCompoundDrawablesWithIntrinsicBounds(0, 0, getIcon(item), 0)
        holder.binding.text.setListener { listener.onRefresh(item) }
        holder.setItem(item)
    }

    private fun getIcon(item: Class): Int =
        if (FilterCache.get(item).isEmpty()) {
            0
        } else if (item.getFilter()) {
            R.drawable.ic_vod_filter_off
        } else {
            R.drawable.ic_vod_filter_on
        }

    open class ViewHolder(
        val binding: AdapterTypeBinding,
        listener: OnClickListener,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Class? = null

        init {
            binding.root.setOnClickListener { item?.let(listener::onItemClick) }
        }

        open fun setItem(item: Class?) {
            this.item = item
        }
    }
}
