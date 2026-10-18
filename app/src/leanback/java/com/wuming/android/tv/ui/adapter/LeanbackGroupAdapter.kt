package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Group
import com.wuming.android.tv.databinding.AdapterGroupBinding
import java.util.Collections

open class LeanbackGroupAdapter(
    private val listener: OnClickListener,
) : BaseSelectAdapter<Group, LeanbackGroupAdapter.ViewHolder>() {
    fun interface OnClickListener {
        fun onItemClick(item: Group)
    }

    open fun add(
        position: Int,
        item: Group,
    ) {
        mItems.add(position, item)
        notifyItemInserted(position)
    }

    open fun unmodifiableList(): List<Group> = Collections.unmodifiableList(mItems)

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
