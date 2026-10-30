package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.EpisodeShortName
import com.wuming.android.tv.bean.History
import com.wuming.android.tv.bean.VodShortName
import com.wuming.android.tv.databinding.AdapterVodBinding
import com.wuming.android.tv.library.UserLibrary
import com.wuming.android.tv.utils.ImgUtil

open class HistoryAdapter(
    @JvmField val listener: OnClickListener,
) : BaseDiffAdapter<History, HistoryAdapter.ViewHolder>() {
    private val gridLayout = VodGridLayout()
    private var delete = false
    private var animate = true

    interface OnClickListener {
        fun onItemClick(item: History)

        fun onItemDelete(item: History)

        fun onLongClick(): Boolean
    }

    open fun setSize(size: IntArray) {
        gridLayout.setSize(size)
        notifyItemRangeChanged(0, itemCount)
    }

    open fun isDelete(): Boolean = delete

    open fun setDelete(delete: Boolean) {
        animate = false
        this.delete = delete
        notifyItemRangeChanged(0, itemCount)
    }

    override fun clear() {
        super.clear()
        setDelete(false)
        UserLibrary.get().deleteHistoryCatalog(VodConfig.getCid())
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val holder = ViewHolder(AdapterVodBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)
        gridLayout.apply(holder.binding)
        return holder
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        gridLayout.apply(holder.binding)
        val item = getItem(position)
        holder.binding.name.text = VodShortName.lookup(item.getVodName())
        holder.binding.site.text = item.getSiteName()
        holder.binding.delete.visibility = if (!delete) View.GONE else View.VISIBLE
        val same = item.getVodName() == item.getVodRemarks()
        holder.binding.remark.text = EpisodeShortName.lookup(item.getVodRemarks())
        holder.binding.site.visibility = item.getSiteVisible()
        val duration = Math.max(0L, item.getDuration())
        val progressMax = Math.min(Int.MAX_VALUE.toLong(), duration).toInt()
        val progress = if (progressMax == 0) 0 else Math.min(progressMax.toLong(), Math.max(0L, item.getPosition())).toInt()
        // Unknown duration remains an empty bar with a valid positive maximum.
        holder.binding.progress.max = Math.max(1, progressMax)
        holder.binding.progress.setProgress(progress, animate)
        holder.binding.remark.visibility = if (delete || same) View.GONE else View.VISIBLE

        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        ImgUtil.clearView(holder.binding.image)
    }

    open class ViewHolder(
        @JvmField val binding: AdapterVodBinding,
        private val adapter: HistoryAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnLongClickListener { adapter.listener.onLongClick() }
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val item = adapter.getItem(pos)
                if (adapter.delete) adapter.listener.onItemDelete(item) else adapter.listener.onItemClick(item)
            }
        }
    }
}
