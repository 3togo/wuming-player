package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.api.config.LiveConfig
import com.wuming.android.tv.bean.Live
import com.wuming.android.tv.databinding.AdapterLiveBinding

open class LiveAdapter(
    private val listener: OnClickListener,
) : RecyclerView.Adapter<LiveAdapter.ViewHolder>() {
    private val mItems = LiveConfig.get().getLives()
    private var action = false

    interface OnClickListener {
        fun onItemClick(item: Live)

        fun onBootClick(
            position: Int,
            item: Live,
        )

        fun onPassClick(
            position: Int,
            item: Live,
        )

        fun onBootLongClick(item: Live): Boolean

        fun onPassLongClick(item: Live): Boolean
    }

    open fun setAction(action: Boolean) {
        this.action = action
    }

    override fun getItemCount(): Int = mItems.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterLiveBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = mItems[position]
        holder.binding.text.text = item.getName()
        holder.binding.text.isSelected = item.isActivated()
        holder.binding.text.isActivated = item.isActivated()
        holder.binding.boot.setImageResource(item.getBootIcon())
        holder.binding.pass.setImageResource(item.getPassIcon())
        holder.binding.boot.visibility = if (action) View.VISIBLE else View.GONE
        holder.binding.pass.visibility = if (action) View.VISIBLE else View.GONE
        holder.setItem(item)
        holder.setPosition(position)
    }

    open class ViewHolder(
        val binding: AdapterLiveBinding,
        listener: OnClickListener,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Live? = null
        private var position = 0

        init {
            binding.text.setOnClickListener { item?.let(listener::onItemClick) }
            binding.boot.setOnClickListener { item?.let { listener.onBootClick(position, it) } }
            binding.pass.setOnClickListener { item?.let { listener.onPassClick(position, it) } }
            binding.boot.setOnLongClickListener { item?.let(listener::onBootLongClick) ?: false }
            binding.pass.setOnLongClickListener { item?.let(listener::onPassLongClick) ?: false }
        }

        open fun setItem(item: Live?) {
            this.item = item
        }

        open fun setPosition(position: Int) {
            this.position = position
        }
    }
}
