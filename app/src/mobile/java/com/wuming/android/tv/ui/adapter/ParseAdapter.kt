package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Parse
import com.wuming.android.tv.databinding.AdapterParseDarkBinding
import com.wuming.android.tv.databinding.AdapterParseLightBinding
import com.wuming.android.tv.ui.base.ViewType

open class ParseAdapter(
    listener: OnClickListener,
    private val viewType: Int,
) : BaseParseAdapter<ParseAdapter.ViewHolder>(listener, true) {
    override fun getItemViewType(position: Int): Int = viewType

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder =
        if (viewType ==
            ViewType.DARK
        ) {
            ViewHolder(AdapterParseDarkBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)
        } else {
            ViewHolder(AdapterParseLightBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)
        }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = mItems[position]
        holder.darkBinding?.let { holder.initView(it.text, item) }
        holder.lightBinding?.let { holder.initView(it.text, item) }
    }

    open class ViewHolder : RecyclerView.ViewHolder {
        val darkBinding: AdapterParseDarkBinding?
        val lightBinding: AdapterParseLightBinding?
        private var item: Parse? = null
        constructor(binding: AdapterParseDarkBinding, listener: OnClickListener) : super(binding.root) {
            darkBinding = binding
            lightBinding = null
            binding.text.setOnClickListener { item?.let(listener::onItemClick) }
        }
        constructor(binding: AdapterParseLightBinding, listener: OnClickListener) : super(binding.root) {
            lightBinding = binding
            darkBinding = null
            binding.text.setOnClickListener { item?.let(listener::onItemClick) }
        }

        open fun initView(
            view: TextView,
            item: Parse,
        ) {
            this.item = item
            view.text = item.getName()
            view.isActivated = item.isActivated()
        }
    }
}
