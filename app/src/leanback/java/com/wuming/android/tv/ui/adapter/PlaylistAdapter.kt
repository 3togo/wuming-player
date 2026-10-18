package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.LeanbackProduct
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.EpisodeShortName
import com.wuming.android.tv.bean.History
import com.wuming.android.tv.bean.VodShortName
import com.wuming.android.tv.databinding.AdapterVodBinding
import com.wuming.android.tv.library.PlaylistStore
import com.wuming.android.tv.ui.custom.PosterFocusEffect
import com.wuming.android.tv.utils.ImgUtil

open class PlaylistAdapter(
    private val listener: Listener,
) : BaseDiffAdapter<History, PlaylistAdapter.ViewHolder>() {
    interface Listener {
        fun onClick(item: History)

        fun onLongClick(item: History): Boolean
    }

    private val width: Int
    private val height: Int

    init {
        val size = LeanbackProduct.calculateItemSize()
        width = size[0]
        height = size[1]
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val holder = ViewHolder(AdapterVodBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)
        holder.binding.root.layoutParams.width = width
        holder.binding.image.layoutParams.height = height
        return holder
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = getItem(position)
        holder.item = item
        holder.binding.name.text = VodShortName.lookup(item.getVodName())
        val selected = PlaylistStore.get().selectedEpisodeCount(item)
        holder.binding.remark.text =
            if (selected ==
                0
            ) {
                EpisodeShortName.lookup(item.getVodRemarks())
            } else {
                holder.itemView.context.getString(R.string.playlist_episode_count, selected)
            }
        holder.binding.site.text = item.getSiteName()
        holder.binding.site.visibility = item.getSiteVisible()
        holder.binding.delete.visibility = View.GONE
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        ImgUtil.clearView(holder.binding.image)
        PosterFocusEffect.reset(holder.itemView)
    }

    open class ViewHolder(
        @JvmField val binding: AdapterVodBinding,
        listener: Listener,
    ) : RecyclerView.ViewHolder(binding.root) {
        @JvmField var item: History? = null

        init {
            itemView.setOnClickListener { item?.let(listener::onClick) }
            itemView.setOnLongClickListener { item?.let(listener::onLongClick) ?: false }
            PosterFocusEffect.attach(itemView)
        }
    }
}
