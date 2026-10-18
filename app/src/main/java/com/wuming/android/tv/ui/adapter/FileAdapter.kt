package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.AdapterFileBinding

open class FileAdapter(
    private val listener: OnClickListener,
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {
    private val items = ArrayList<java.io.File>()

    fun interface OnClickListener {
        fun onItemClick(item: java.io.File)
    }

    open fun addAll(items: List<java.io.File>) {
        this.items.clear()
        this.items.addAll(items)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterFileBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.bind(items[position])
    }

    open class ViewHolder(
        val binding: AdapterFileBinding,
        private val adapter: FileAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: java.io.File? = null

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                val current = item
                if (pos != RecyclerView.NO_POSITION && current != null) {
                    adapter.listener.onItemClick(current)
                }
            }
        }

        open fun bind(item: java.io.File) {
            this.item = item
            binding.name.text = item.name
            binding.image.setImageResource(if (item.isDirectory) R.drawable.ic_folder else R.drawable.ic_file)
        }
    }
}
