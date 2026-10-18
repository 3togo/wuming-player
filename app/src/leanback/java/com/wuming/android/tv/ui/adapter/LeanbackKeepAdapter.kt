package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.LeanbackProduct
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Keep
import com.wuming.android.tv.bean.VodShortName
import com.wuming.android.tv.databinding.AdapterVodBinding
import com.wuming.android.tv.ui.custom.PosterFocusEffect
import com.wuming.android.tv.utils.ImgUtil

open class LeanbackKeepAdapter(
    private val listener: OnClickListener,
) : BaseDiffAdapter<Keep, LeanbackKeepAdapter.ViewHolder>() {
    private var width = 0
    private var height = 0
    private var margin = 0
    private var delete = false

    interface OnClickListener {
        fun onItemClick(item: Keep)

        fun onItemDelete(item: Keep)

        fun onLongClick(): Boolean
    }

    init {
        setLayoutSize()
    }

    private fun setLayoutSize() {
        val size = LeanbackProduct.calculateItemSize()
        width = size[0]
        height = size[1]
    }

    open fun isDelete(): Boolean = delete

    open fun setDelete(delete: Boolean) {
        this.delete = delete
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val holder = ViewHolder(AdapterVodBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)
        holder.binding.root.layoutParams.width = width
        holder.binding.image.layoutParams.height = height
        return holder
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = getItem(position)
        holder.binding.name.text = VodShortName.lookup(item.vodName)
        holder.binding.site.text = item.siteName
        holder.binding.delete.visibility = if (!delete) View.GONE else View.VISIBLE
        holder.setItem(item)
        holder.binding.remark.visibility = View.GONE
        holder.binding.site.visibility = View.VISIBLE

        ImgUtil.load(item.vodName, item.vodPic, holder.binding.image)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        ImgUtil.clearView(holder.binding.image)
        PosterFocusEffect.reset(holder.itemView)
    }

    open class ViewHolder(
        val binding: AdapterVodBinding,
        private val adapter: LeanbackKeepAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Keep? = null

        open fun setItem(item: Keep?) {
            this.item = item
        }

        init {
            binding.root.setOnLongClickListener { adapter.listener.onLongClick() }
            binding.root.setOnClickListener {
                val item = this.item
                if (item != null) {
                    if (adapter.isDelete()) adapter.listener.onItemDelete(item) else adapter.listener.onItemClick(item)
                }
            }
            PosterFocusEffect.attach(itemView)
        }
    }
}
