package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Style
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.databinding.AdapterVodListBinding
import com.wuming.android.tv.databinding.AdapterVodOvalBinding
import com.wuming.android.tv.databinding.AdapterVodRectBinding
import com.wuming.android.tv.ui.base.BaseVodHolder
import com.wuming.android.tv.ui.base.ViewType
import com.wuming.android.tv.ui.base.VodClickListener
import com.wuming.android.tv.ui.holder.MobileVodRectHolder
import com.wuming.android.tv.ui.holder.VodListHolder
import com.wuming.android.tv.ui.holder.VodOvalHolder

open class VodAdapter(
    listener: OnClickListener,
    private val style: Style,
    private var size: IntArray,
) : BaseDiffAdapter<Vod, BaseVodHolder>() {
    private val listener: VodClickListener = listener

    interface OnClickListener : VodClickListener

    open fun getStyle(): Style = style

    open fun setSize(size: IntArray) {
        this.size = size
        android.util.Log.d("VodAdapter", "setSize: width=" + size[0] + " height=" + size[1])
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = style.getViewType()

    override fun onBindViewHolder(
        holder: BaseVodHolder,
        position: Int,
    ) {
        val item = getItem(position)
        if (holder is MobileVodRectHolder) {
            holder.size(size)
        } else if (holder is VodOvalHolder) {
            holder.size(size)
        }
        holder.itemView.contentDescription = item.getSearchDisplayName()
        holder.initView(item)
    }

    override fun onViewRecycled(holder: BaseVodHolder) {
        holder.unbind()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): BaseVodHolder =
        when (viewType) {
            ViewType.LIST -> {
                VodListHolder(AdapterVodListBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)
            }

            ViewType.OVAL -> {
                VodOvalHolder(
                    AdapterVodOvalBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                    listener,
                ).size(size)
            }

            else -> {
                MobileVodRectHolder(AdapterVodRectBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener).size(size)
            }
        }
}
