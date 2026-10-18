package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.databinding.AdapterDohBinding
import com.github.catvod.bean.Doh

open class DohAdapter(
    private val listener: OnClickListener,
) : RecyclerView.Adapter<DohAdapter.ViewHolder>() {
    private val items = VodConfig.get().getDoh()
    private var select = 0

    fun interface OnClickListener {
        fun onItemClick(item: Doh)
    }

    open fun setSelect(select: Int) {
        this.select = select
    }

    open fun getSelect(): Int = select

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterDohBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.bind(items[position], position)
    }

    open class ViewHolder(
        private val binding: AdapterDohBinding,
        private val adapter: DohAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.text.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) adapter.listener.onItemClick(adapter.items[pos])
            }
        }

        open fun bind(
            item: Doh,
            position: Int,
        ) {
            binding.text.text = item.getName()
            binding.text.isActivated = adapter.select == position
        }
    }
}
