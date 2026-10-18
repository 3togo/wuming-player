package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Word
import com.wuming.android.tv.databinding.AdapterSearchWordBinding

open class WordAdapter(
    private val listener: OnClickListener,
) : BaseDiffAdapter<Word.Data, WordAdapter.ViewHolder>() {
    fun interface OnClickListener {
        fun onItemClick(text: String)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterSearchWordBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position))
    }

    open class ViewHolder(
        val binding: AdapterSearchWordBinding,
        private val adapter: WordAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Word.Data? = null

        init {
            binding.text.setOnClickListener {
                val pos = bindingAdapterPosition
                val current = item
                if (pos != RecyclerView.NO_POSITION && current != null) {
                    adapter.listener.onItemClick(current.getTitle())
                }
            }
        }

        open fun bind(item: Word.Data) {
            this.item = item
            binding.text.text = item.getTitle()
        }
    }
}
