package com.wuming.android.tv.ui.adapter

import com.wuming.android.tv.utils.TextScale

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.LeanbackProduct
import com.wuming.android.tv.R
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.Style
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.utils.ImgUtil
import com.wuming.android.tv.utils.SearchResultMerger
import java.util.ArrayList

open class LeanbackFastSearchAdapter : RecyclerView.Adapter<LeanbackFastSearchAdapter.ViewHolder>() {
    private val items = ArrayList<Vod>()
    private var listener: OnItemClickListener? = null

    fun interface OnItemClickListener {
        fun onItemClick(item: Vod)
    }

    open fun setOnItemClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    open fun setNewData(data: List<Vod>?) {
        items.clear()
        if (data !=
            null
        ) {
            items.addAll(SearchResultMerger.merge(data))
        }
        notifyDataSetChanged()
    }

    open fun addData(data: List<Vod>?) {
        if (!data.isNullOrEmpty()) {
            items.addAll(data)
            val merged = SearchResultMerger.merge(items)
            items.clear()
            items.addAll(merged)
            notifyDataSetChanged()
        }
    }

    open fun getData(): MutableList<Vod> = items

    open fun sort(comparator: java.util.Comparator<in Vod>?) {
        if (items.size >
            1
        ) {
            java.util.Collections.sort(items, comparator)
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_fast_search, parent, false)
        val holder = ViewHolder(view, this)
        val size = LeanbackProduct.getSearchSpec(Style.rect())
        val params = view.layoutParams
        if (params != null && size.size > 1) {
            params.width = size[0]
            params.height = size[1]
            view.layoutParams = params
        }
        if (size.size >
            3
        ) {
            val imageParams = holder.ivThumb.layoutParams
            imageParams.height = size[3]
            holder.ivThumb.layoutParams = imageParams
        }
        TextScale.bind(holder.tvName)
        TextScale.bind(holder.tvNote)
        holder.tvSite?.let { TextScale.bind(it) }
        return holder
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.unbind()
    }

    override fun getItemCount(): Int = items.size

    open class ViewHolder(
        itemView: View,
        private val adapter: LeanbackFastSearchAdapter,
    ) : RecyclerView.ViewHolder(itemView) {
        val ivThumb: ImageView = itemView.findViewById(R.id.ivThumb)
        val tvName: TextView = itemView.findViewById(R.id.tvName)
        val tvSite: TextView? = itemView.findViewById(R.id.tvSite)
        val tvNote: TextView = itemView.findViewById(R.id.tvNote)
        private var currentItem: Vod? = null

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                val current = currentItem
                if (pos !=
                    RecyclerView.NO_POSITION &&
                    current != null
                ) {
                    adapter.listener?.onItemClick(current)
                }
            }
        }

        open fun bind(item: Vod) {
            currentItem = item

            tvName.text = item.getSearchDisplayName()

            val siteName = if (VodConfig.get().getSite(item.getSiteKey()) != null) VodConfig.get().getSite(item.getSiteKey()).name else ""
            tvSite!!.text = siteName
            tvNote.visibility = if (item.remarks.isEmpty()) View.GONE else View.VISIBLE
            if (item.remarks.isNotEmpty()) tvNote.text = item.remarks
            if (item.pic.isNotEmpty()) {
                ImgUtil.load(
                    item.name,
                    item.pic,
                    ivThumb,
                )
            } else {
                ivThumb.setImageResource(R.drawable.img_loading_placeholder)
            }
        }

        open fun unbind() {
            ImgUtil.clearView(ivThumb)
            currentItem = null
        }
    }
}
