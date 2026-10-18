package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Danmaku
import com.wuming.android.tv.databinding.AdapterDanmakuBinding

open class DanmakuAdapter(
    private val listener: OnClickListener,
) : RecyclerView.Adapter<DanmakuAdapter.ViewHolder>() {
    private val items = ArrayList<Danmaku>()

    fun interface OnClickListener {
        fun onItemClick(item: Danmaku)
    }

    open fun addAll(items: List<Danmaku>?): DanmakuAdapter {
        if (items == null) return this
        this.items.addAll(items)
        notifyDataSetChanged()
        return this
    }

    open fun clear() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }

    open fun getSelected(): Int = items.indexOfFirst { it.isSelected() }.coerceAtLeast(0)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterDanmakuBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.bind(items[position])
    }

    open class ViewHolder(
        val binding: AdapterDanmakuBinding,
        private val adapter: DanmakuAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Danmaku? = null

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                val current = item
                if (pos != RecyclerView.NO_POSITION) {
                    adapter.listener.onItemClick(adapter.items[pos])
                }
            }
        }

        open fun bind(item: Danmaku) {
            this.item = item
            binding.text.text = item.getName()
            binding.text.isActivated = item.isSelected()
        }
    }
}
