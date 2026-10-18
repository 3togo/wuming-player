package com.wuming.android.tv.ui.adapter

import com.wuming.android.tv.utils.TextScale

import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.databinding.AdapterSiteBinding
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.SiteRanker
import java.lang.ref.WeakReference
import java.util.ArrayList

open class LeanbackSiteAdapter(
    listener: OnClickListener?,
) : BaseSelectAdapter<Site, LeanbackSiteAdapter.ViewHolder>() {
    private val listenerRef = WeakReference(listener)
    private var isGrid = false
    private var spanCount = 1
    private var type = 0
    private var isDialog = false

    companion object {
        const val TYPE_SELECT = 0
        const val TYPE_SEARCH = 1
        const val TYPE_CHANGE = 2
    }

    fun interface OnClickListener {
        fun onItemClick(item: Site)
    }

    open fun setDialog(isDialog: Boolean): LeanbackSiteAdapter {
        this.isDialog = isDialog
        return this
    }

    open fun setType(type: Int) {
        this.type = type
        notifyDataSetChanged()
    }

    open fun selectAll() {
        setEnable(true)
    }

    open fun cancelAll() {
        setEnable(false)
    }

    init {
        addAll()
    }

    open fun setGridMode(isGrid: Boolean) {
        this.isGrid = isGrid
    }

    open fun setSpanCount(spanCount: Int) {
        this.spanCount = spanCount
    }

    open fun clearListener() {
        listenerRef.clear()
    }

    override fun isActivated(item: Site): Boolean = item.isActivated()

    private fun addAll() {
        try {
            val sites = ArrayList<Site>()
            for (site in VodConfig.get().getSites()) if (!site.isHide()) sites.add(site)
            val allSite = if (sites.isEmpty()) null else sites.removeAt(0)
            val ranked = SiteRanker.get().rankByPerformance(sites)
            if (allSite != null) mItems.add(allSite)
            mItems.addAll(ranked)
        } catch (_: Exception) {
            for (site in VodConfig.get().getSites()) if (!site.isHide()) mItems.add(site)
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val binding = AdapterSiteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        applyItemSizing(binding)
        return ViewHolder(binding, this)
    }

    private fun applyItemSizing(binding: AdapterSiteBinding) {
        val rootParams = binding.root.layoutParams
        val itemHeight = if (isDialog) LayoutConfig.getSiteDialogItemHeightPixels(binding.root.context) else ResUtil.getSiteButtonHeight()
        if (rootParams != null) {
            rootParams.height = if (isDialog) ViewGroup.LayoutParams.WRAP_CONTENT else itemHeight
            if (isDialog) binding.root.minimumHeight = itemHeight
            binding.root.layoutParams = rootParams
        }
        if (isDialog) {
            TextScale.setRole(binding.text, com.wuming.tv.core.design.TextRole.LABEL)
        } else {
            TextScale.bind(binding.text)
        }
        val padding = LayoutConfig.getSiteDialogTextHorizontalPaddingPixels(itemHeight)
        binding.text.setPadding(padding, 0, padding, 0)
        binding.text.ellipsize = TextUtils.TruncateAt.END
        val iconSize = if (isDialog) ResUtil.getSiteDialogIconSize() else ResUtil.getSiteIconSize()
        val checkParams = binding.check.layoutParams
        if (checkParams != null) {
            checkParams.width = iconSize
            checkParams.height = iconSize
            binding.check.layoutParams = checkParams
        }
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = mItems[position]
        bindText(holder, item)
        bindCheck(holder, item)
        holder.binding.text.gravity = if (isGrid) Gravity.START else Gravity.CENTER
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        clearListener()
        mItems.clear()
    }

    private fun bindText(
        holder: ViewHolder,
        item: Site,
    ) {
        val info = SiteRanker.get().getRankingInfo(item.key)
        holder.binding.text.text = if (isDialog) formatRankingText(item.name, info) else item.name
        holder.binding.text.isSelected = item.isActivated()
        holder.binding.text.isActivated = item.isActivated()
        holder.binding.root.isActivated = item.isActivated()
    }

    private fun formatRankingText(
        name: String,
        info: SiteRanker.RankingInfo,
    ): String =
        if (info.hasData) {
            String.format(
                java.util.Locale.getDefault(),
                "%s (%.0f%%)",
                name,
                info.performanceScore * 100,
            )
        } else {
            name
        }

    private fun bindCheck(
        holder: ViewHolder,
        item: Site,
    ) {
        holder.binding.check.isChecked = isChecked(item)
        holder.binding.check.visibility = if (type == TYPE_SELECT) View.GONE else View.VISIBLE
    }

    private fun isChecked(item: Site): Boolean =
        when (type) {
            TYPE_SEARCH -> item.isSearchable()
            TYPE_CHANGE -> item.isChangeable()
            else -> false
        }

    open fun handleClick(position: Int) {
        if (position == RecyclerView.NO_POSITION || position >= mItems.size) return
        val item = mItems[position]
        if (type == TYPE_SELECT) {
            listenerRef.get()?.onItemClick(item)
        } else {
            toggleItem(item)
            notifyItemChanged(position)
        }
    }

    open fun handleLongClick(position: Int): Boolean {
        if (position == RecyclerView.NO_POSITION || position >= mItems.size) return false
        val item = mItems[position]
        if (type == TYPE_SEARCH) {
            setEnable(!item.isSearchable())
        } else if (type == TYPE_CHANGE) {
            setEnable(!item.isChangeable())
        }
        return true
    }

    private fun toggleItem(item: Site) {
        if (type ==
            TYPE_SEARCH
        ) {
            item.setSearchable(!item.isSearchable()).save()
        } else if (type ==
            TYPE_CHANGE
        ) {
            item.setChangeable(!item.isChangeable()).save()
        }
    }

    private fun setEnable(enable: Boolean) {
        if (type == TYPE_SEARCH) {
            for (site in mItems) site.setSearchable(enable).save()
        } else if (type == TYPE_CHANGE) {
            for (site in mItems) site.setChangeable(enable).save()
        }
        notifyItemRangeChanged(0, itemCount)
    }

    open class ViewHolder(
        @JvmField val binding: AdapterSiteBinding,
        adapter: LeanbackSiteAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener { adapter.handleClick(bindingAdapterPosition) }
            binding.root.setOnLongClickListener { adapter.handleLongClick(bindingAdapterPosition) }
        }
    }
}
