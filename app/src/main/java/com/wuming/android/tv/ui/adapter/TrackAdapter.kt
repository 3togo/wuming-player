package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Track
import com.wuming.android.tv.databinding.AdapterTrackBinding

open class TrackAdapter(private val listener: OnClickListener) :
    RecyclerView.Adapter<TrackAdapter.ViewHolder>() {

    private val items = ArrayList<Track>()

    fun interface OnClickListener {
        fun onItemClick(item: Track)
    }

    open fun replaceAll(items: List<Track>): TrackAdapter {
        val previousSize = this.items.size
        this.items.clear()
        if (previousSize > 0) notifyItemRangeRemoved(0, previousSize)
        this.items.addAll(items)
        if (items.isNotEmpty()) notifyItemRangeInserted(0, items.size)
        return this
    }

    open fun clear() {
        if (items.isEmpty()) return
        val previousSize = items.size
        items.clear()
        notifyItemRangeRemoved(0, previousSize)
    }

    open fun getSelected(): Int = items.indexOfFirst { it.isSelected }.coerceAtLeast(0)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            AdapterTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            this,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    open class ViewHolder(
        private val binding: AdapterTrackBinding,
        private val adapter: TrackAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    adapter.listener.onItemClick(adapter.items[position].toggle())
                }
            }
        }

        open fun bind(item: Track) {
            binding.text.text = item.name
            binding.text.isActivated = item.isSelected
        }
    }
}
