package com.wuming.android.tv.ui.adapter

import com.wuming.android.tv.utils.TextScale

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Collect
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.databinding.AdapterCollectBinding

open class MobileCollectAdapter(
    private val listener: OnClickListener,
) : BaseDiffAdapter<Collect, MobileCollectAdapter.ViewHolder>() {
    fun interface OnClickListener {
        fun onItemClick(
            position: Int,
            item: Collect,
        )
    }

    open fun add(items: List<Vod>) {
        if (itemCount == 0) return
        val current = getItem(0).getList().toMutableList()
        current.addAll(items)
        getItem(0).setList(current)
    }

    open fun getPosition(): Int {
        for (i in 0 until itemCount) if (getItem(i).isActivated()) return i
        return 0
    }

    open fun getActivated(): Collect = getItems()[getPosition()]

    open fun setActivated(position: Int) {
        for (i in 0 until itemCount) getItem(i).setActivated(i == position)
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val holder = ViewHolder(AdapterCollectBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)

        return holder
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = getItem(position)
        holder.binding.text.text = item.getSite().name
        holder.binding.text.isSelected = item.isActivated()
        holder.binding.text.isActivated = item.isActivated()
        TextScale.bind(holder.binding.text)
        holder.setPosition(position)
        holder.setItem(item)
    }

    open class ViewHolder(
        val binding: AdapterCollectBinding,
        listener: OnClickListener,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Collect? = null
        private var position = 0

        init {
            binding.text.setOnClickListener { item?.let { listener.onItemClick(position, it) } }
        }

        open fun setItem(item: Collect?) {
            this.item = item
        }

        open fun setPosition(position: Int) {
            this.position = position
        }
    }
}
