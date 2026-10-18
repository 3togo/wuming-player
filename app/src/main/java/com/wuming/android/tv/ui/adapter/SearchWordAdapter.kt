package com.wuming.android.tv.ui.adapter

import com.wuming.android.tv.utils.TextScale

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.ProductCore
import com.wuming.android.tv.R

open class SearchWordAdapter : RecyclerView.Adapter<SearchWordAdapter.ViewHolder>() {
    private val items = ArrayList<String>()
    private var listener: OnItemClickListener? = null

    fun interface OnItemClickListener {
        fun onItemClick(item: String)
    }

    open fun setOnItemClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    open fun setNewData(data: List<String>?) {
        items.clear()
        if (data != null) items.addAll(data)
        notifyDataSetChanged()
    }

    open fun getData(): MutableList<String> = items

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_search_word_split, parent, false), this)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        if (ProductCore.getDeviceType() ==
            1
        ) {
            TextScale.bind(holder.tvSearchWord)
        }
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    open class ViewHolder(
        itemView: View,
        private val adapter: SearchWordAdapter,
    ) : RecyclerView.ViewHolder(itemView) {
        val tvSearchWord: TextView = itemView.findViewById(R.id.tvSearchWord)
        private var item: String? = null

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                val current = item
                if (pos != RecyclerView.NO_POSITION && current != null) adapter.listener?.onItemClick(current)
            }
        }

        open fun bind(item: String) {
            this.item = item
            tvSearchWord.text = item
        }
    }
}
