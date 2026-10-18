package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.EpgData
import com.wuming.android.tv.databinding.AdapterEpgDataBinding

open class EpgDataAdapter(
    private val listener: OnClickListener,
) : BaseSelectAdapter<EpgData, EpgDataAdapter.ViewHolder>() {
    interface OnClickListener {
        fun hideEpg() {}

        fun onItemClick(item: EpgData)
    }

    open fun setSelected(item: EpgData) {
        setSelected(mItems.indexOf(item))
    }

    open fun setSelected(position: Int) {
        for (i in mItems.indices) mItems[i].setSelected(i == position)
        notifyItemRangeChanged(0, itemCount)
    }

    override fun isActivated(item: EpgData): Boolean = item.isSelected()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterEpgDataBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.bind(mItems[position])
    }

    companion object {
        private fun setLeftListener(
            root: View,
            action: Runnable,
        ) {
            try {
                root.javaClass.getMethod("setLeftListener", Runnable::class.java).invoke(root, action)
            } catch (_: Exception) {
            }
        }
    }

    open class ViewHolder(
        private val binding: AdapterEpgDataBinding,
        listener: OnClickListener,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: EpgData? = null

        init {
            setLeftListener(binding.root, Runnable { listener.hideEpg() })
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                val current = item
                if (pos != RecyclerView.NO_POSITION && current != null && !current.isFuture()) listener.onItemClick(current)
            }
        }

        open fun bind(item: EpgData) {
            this.item = item
            binding.time.text = item.getTime()
            binding.title.text = item.getTitle()
            binding.root.isSelected =
                item.isSelected()
        }
    }
}
