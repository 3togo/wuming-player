package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.wuming.android.tv.bean.Channel
import com.wuming.android.tv.databinding.AdapterChannelBinding

open class ChannelAdapter(
    private val listener: OnClickListener,
) : BaseSelectAdapter<Channel, ChannelAdapter.ViewHolder>() {
    interface OnClickListener {
        fun showEpg(item: Channel) {}

        fun onItemClick(item: Channel)

        fun onLongClick(item: Channel): Boolean
    }

    open fun remove(item: Channel) {
        val position = mItems.indexOf(item)
        if (position == -1) return
        mItems.removeAt(position)
        notifyItemRemoved(position)
    }

    open fun setSelected(channel: Channel): Int {
        val position = mItems.indexOf(channel)
        setSelected(position)
        return position
    }

    open fun setSelected(position: Int) {
        if (position == -1) return
        for (i in mItems.indices) mItems[i].setSelected(i == position)
        notifyItemRangeChanged(0, itemCount)
    }

    override fun isActivated(item: Channel): Boolean = item.isSelected()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = mItems[position]
        item.loadLogo(holder.binding.logo)
        holder.binding.name.text = item.getShow()
        holder.binding.number.text = item.getNumber()
        holder.binding.root.isSelected = item.isSelected()
        holder.setItem(item)
        setRightListener(holder.binding.root, Runnable { listener.showEpg(item) })
    }

    override fun onViewRecycled(holder: ViewHolder) {
        try {
            if (holder.binding.logo.isAttachedToWindow) Glide.with(holder.binding.logo).clear(holder.binding.logo)
        } catch (
            _: Exception,
        ) {
        }
    }

    companion object {
        private fun setRightListener(
            root: View,
            action: Runnable,
        ) {
            try {
                root.javaClass.getMethod("setRightListener", Runnable::class.java).invoke(root, action)
            } catch (_: Exception) {
            }
        }
    }

    open class ViewHolder(
        val binding: AdapterChannelBinding,
        listener: OnClickListener,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Channel? = null

        init {
            binding.root.setOnClickListener { item?.let(listener::onItemClick) }
            binding.root.setOnLongClickListener { item?.let(listener::onLongClick) ?: false }
        }

        open fun setItem(item: Channel?) {
            this.item = item
        }
    }
}
