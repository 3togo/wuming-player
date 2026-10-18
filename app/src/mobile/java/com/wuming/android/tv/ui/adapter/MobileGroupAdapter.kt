package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Group
import com.wuming.android.tv.databinding.AdapterGroupBinding

open class MobileGroupAdapter(
    private val listener: OnClickListener,
) : BaseSelectAdapter<Group, MobileGroupAdapter.ViewHolder>() {
    interface OnClickListener {
        fun setWidth(item: Group)

        fun onItemClick(item: Group)
    }

    open fun add(item: Group) {
        mItems.add(item)
        notifyItemInserted(itemCount - 1)
    }

    override fun getPosition(): Int = mItems.indexOfFirst { it.isSelected() }.coerceAtLeast(0)

    open fun setSelected(group: Group) {
        setSelected(mItems.indexOf(group))
    }

    open fun setSelected(position: Int) {
        for (i in mItems.indices) mItems[i].setSelected(i == position)
        notifyItemRangeChanged(0, itemCount)
        listener.setWidth(mItems[position])
    }

    override fun isActivated(item: Group): Boolean = item.isSelected()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = mItems[position]
        holder.binding.name.text = item.getName()
        holder.binding.root.isSelected = item.isSelected()
        holder.setItem(item)
    }

    open class ViewHolder(
        val binding: AdapterGroupBinding,
        listener: OnClickListener,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Group? = null

        init {
            binding.root.setOnClickListener { item?.let(listener::onItemClick) }
        }

        open fun setItem(item: Group?) {
            this.item = item
        }
    }
}
