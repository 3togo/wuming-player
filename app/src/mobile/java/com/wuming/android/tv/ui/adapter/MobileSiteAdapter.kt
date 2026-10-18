package com.wuming.android.tv.ui.adapter

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.databinding.AdapterSiteBinding
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.SiteRanker
import java.lang.ref.WeakReference
import java.util.ArrayList

open class MobileSiteAdapter(
    listener: OnClickListener?,
) : BaseSelectAdapter<Site, MobileSiteAdapter.ViewHolder>() {
    private val listenerRef = WeakReference(listener)
    private var isGrid = false
    private var spanCount = 1
    private var search = false
    private var change = false

    interface OnClickListener {
        fun onTextClick(item: Site)

        fun onSearchClick(
            position: Int,
            item: Site,
        )

        fun onChangeClick(
            position: Int,
            item: Site,
        )

        fun onSearchLongClick(item: Site): Boolean

        fun onChangeLongClick(item: Site): Boolean
    }

    open fun search(search: Boolean): MobileSiteAdapter {
        this.search = search
        return this
    }

    open fun change(change: Boolean): MobileSiteAdapter {
        this.change = change
        return this
    }

    open fun getListener(): OnClickListener? = listenerRef.get()

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
        val sites = ArrayList<Site>()
        for (site in VodConfig.get().getSites()) if (!site.isHide()) sites.add(site)
        val allSite = if (sites.isEmpty()) null else sites.removeAt(0)
        val ranked = SiteRanker.get().rankByPerformance(sites)
        if (allSite != null) mItems.add(allSite)
        mItems.addAll(ranked)
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
        val itemHeight = LayoutConfig.getSiteDialogItemHeightPixels(binding.root.context)
        if (rootParams != null) {
            rootParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            binding.root.minimumHeight = itemHeight
            binding.root.layoutParams = rootParams
        }
        com.wuming.android.tv.utils.TextScale.setRole(binding.text, com.wuming.tv.core.design.TextRole.LABEL)
        val padding = LayoutConfig.getSiteDialogTextHorizontalPaddingPixels(itemHeight)
        binding.text.setPadding(padding, 0, padding, 0)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = mItems[position]
        com.wuming.android.tv.utils.TextScale.setRole(holder.binding.text, com.wuming.tv.core.design.TextRole.LABEL)
        bindText(holder, item)
        bindIcons(holder, item)
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
        val on = !search || change
        holder.binding.text.text = formatRankingText(item.name, info)
        holder.binding.text.isEnabled = on
        holder.binding.text.isFocusable = on
        holder.binding.text.isSelected = on && item.isActivated()
        holder.binding.text.isActivated = on && item.isActivated()
        holder.binding.root.isActivated = on && item.isActivated()
        holder.binding.text.gravity = if (isGrid) Gravity.START else Gravity.CENTER
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

    private fun bindIcons(
        holder: ViewHolder,
        item: Site,
    ) {
        holder.binding.search.setImageResource(if (item.isSearchable()) R.drawable.ic_site_search else R.drawable.ic_site_block)
        holder.binding.change.setImageResource(if (item.isChangeable()) R.drawable.ic_site_change else R.drawable.ic_site_block)
        val showIcons = !isGrid && (search || change)
        holder.binding.search.visibility = if (showIcons && search) View.VISIBLE else View.GONE
        holder.binding.change.visibility = if (showIcons && change) View.VISIBLE else View.GONE
    }

    open class ViewHolder(
        @JvmField val binding: AdapterSiteBinding,
        adapter: MobileSiteAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos !=
                    RecyclerView.NO_POSITION
                ) {
                    adapter.getListener()?.onTextClick(adapter.mItems[pos])
                }
            }
            binding.search.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos !=
                    RecyclerView.NO_POSITION
                ) {
                    adapter.getListener()?.onSearchClick(pos, adapter.mItems[pos])
                }
            }
            binding.change.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos !=
                    RecyclerView.NO_POSITION
                ) {
                    adapter.getListener()?.onChangeClick(pos, adapter.mItems[pos])
                }
            }
            binding.search.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos !=
                    RecyclerView.NO_POSITION
                ) {
                    adapter.getListener()?.onSearchLongClick(adapter.mItems[pos]) ?: false
                } else {
                    false
                }
            }
            binding.change.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos !=
                    RecyclerView.NO_POSITION
                ) {
                    adapter.getListener()?.onChangeLongClick(adapter.mItems[pos]) ?: false
                } else {
                    false
                }
            }
        }
    }
}
