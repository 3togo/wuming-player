package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Keep
import com.wuming.android.tv.bean.VodShortName
import com.wuming.android.tv.databinding.AdapterVodBinding
import com.wuming.android.tv.library.UserLibrary
import com.wuming.android.tv.utils.ImgUtil

open class MobileKeepAdapter(
    private val listener: OnClickListener,
) : BaseDiffAdapter<Keep, MobileKeepAdapter.ViewHolder>() {
    private var width = 0
    private var height = 0
    private var margin = 0
    private var delete = false

    interface OnClickListener {
        fun onItemClick(item: Keep)

        fun onItemDelete(item: Keep)

        fun onLongClick(): Boolean
    }

    open fun setSize(size: IntArray) {
        width = size[0]
        // adapter_vod uses the image-only height at index 3.
        height = size[3]
        margin = if (size.size > 2) size[2] else 0
        notifyItemRangeChanged(0, itemCount)
    }

    open fun isDelete(): Boolean = delete

    open fun setDelete(delete: Boolean) {
        this.delete = delete
        notifyItemRangeChanged(0, itemCount)
    }

    override fun clear() {
        super.clear()
        setDelete(false)
        UserLibrary.get().deleteAllVideoFavorites()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val holder = ViewHolder(AdapterVodBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)
        applySize(holder)
        return holder
    }

    private fun applySize(holder: ViewHolder) {
        val params = holder.binding.root.layoutParams as ViewGroup.MarginLayoutParams
        params.width = width
        params.setMargins(margin, margin, margin, margin)
        holder.binding.root.layoutParams = params
        holder.binding.image.layoutParams.height = height
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        applySize(holder)
        val item = getItem(position)
        holder.binding.name.text = VodShortName.lookup(item.vodName)
        holder.binding.site.text = item.siteName
        holder.binding.delete.visibility = if (!delete) View.GONE else View.VISIBLE
        holder.setItem(item)
        holder.binding.remark.visibility = View.GONE
        holder.binding.site.visibility = View.VISIBLE
        holder.binding.progress.visibility = View.GONE
        ImgUtil.load(item.vodName, item.vodPic, holder.binding.image)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        ImgUtil.clearView(holder.binding.image)
    }

    open class ViewHolder(
        val binding: AdapterVodBinding,
        private val adapter: MobileKeepAdapter,
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
        }
    }
}
