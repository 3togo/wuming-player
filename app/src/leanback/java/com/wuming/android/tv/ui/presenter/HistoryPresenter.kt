package com.wuming.android.tv.ui.presenter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import com.wuming.android.tv.LeanbackProduct
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.EpisodeShortName
import com.wuming.android.tv.bean.History
import com.wuming.android.tv.bean.VodShortName
import com.wuming.android.tv.databinding.AdapterVodBinding
import com.wuming.android.tv.utils.ImgUtil

open class HistoryPresenter(
    private val listener: OnClickListener,
) : Presenter() {
    private var width = 0
    private var height = 0
    private var delete = false

    init {
        setLayoutSize()
    }

    interface OnClickListener {
        fun onItemClick(item: History)

        fun onItemDelete(item: History)

        fun onLongClick(item: History): Boolean
    }

    private fun setLayoutSize() {
        val size = LeanbackProduct.calculateItemSize()
        width = size[0]
        height = size[1]
    }

    open fun isDelete(): Boolean = delete

    open fun setDelete(delete: Boolean) {
        this.delete = delete
    }

    private fun setClickListener(
        root: View,
        item: History,
    ) {
        root.setOnLongClickListener { listener.onLongClick(item) }
        root.setOnClickListener { if (isDelete()) listener.onItemDelete(item) else listener.onItemClick(item) }
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val holder = ViewHolder(AdapterVodBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        holder.binding.root.layoutParams.width = width
        holder.binding.image.layoutParams.height = height
        return holder
    }

    override fun onBindViewHolder(
        viewHolder: Presenter.ViewHolder,
        item: Any?,
    ) {
        val history = item as History
        val same = history.getVodName() == history.getVodRemarks()
        val holder = viewHolder as ViewHolder
        setClickListener(holder.view, history)
        holder.binding.name.text = VodShortName.lookup(history.getVodName())
        holder.binding.site.text = history.getSiteName()
        holder.binding.remark.text = EpisodeShortName.lookup(history.getVodRemarks())
        holder.binding.site.visibility = history.getSiteVisible()
        holder.binding.delete.visibility = if (!delete) View.GONE else View.VISIBLE
        holder.binding.remark.visibility = if (delete || same) View.GONE else View.VISIBLE
        ImgUtil.load(history.getVodName(), history.getVodPic(), holder.binding.image)
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val holder = viewHolder as ViewHolder
        ImgUtil.clearView(holder.binding.image)
    }

    open class ViewHolder(
        val binding: AdapterVodBinding,
    ) : Presenter.ViewHolder(binding.root)
}
