package com.wuming.android.tv.ui.adapter

import com.wuming.android.tv.utils.TextScale

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.SitePerformance
import com.wuming.android.tv.databinding.AdapterSiteRankingBinding
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.SiteRanker
import java.util.Locale

open class SiteRankingAdapter : RecyclerView.Adapter<SiteRankingAdapter.ViewHolder>() {
    companion object {
        const val SORT_PERFORMANCE = 0
        const val SORT_RELIABILITY = 1
        const val SORT_SPEED = 2
        private const val MIN_REQUESTS = 3

        // Display normalization: 10 MB/s = 10,000,000 bytes/s.
        private const val MAX_DISPLAY_SPEED_BYTES = 10_000_000L
    }

    private val items = ArrayList<RankingItem>()
    private val selectedKeys = HashSet<String>()
    private var sortMode = SORT_PERFORMANCE
    private var selectMode = false
    private var clickListener: OnItemClickListener? = null
    private var onSelectionChanged: Runnable? = null

    fun interface OnItemClickListener {
        fun onItemClick(siteKey: String)
    }

    open fun setOnItemClickListener(listener: OnItemClickListener?) {
        clickListener = listener
    }

    open fun setOnSelectionChanged(callback: Runnable?) {
        onSelectionChanged = callback
    }

    open class RankingItem(
        @JvmField val siteKey: String,
        @JvmField val siteName: String,
        @JvmField val performance: SitePerformance?,
        @JvmField val rank: Int,
    )

    open fun setSortMode(mode: Int) {
        sortMode = mode
        refresh()
    }

    open fun getSortMode(): Int = sortMode

    open fun setSelectMode(enabled: Boolean) {
        selectMode = enabled
        if (!enabled) selectedKeys.clear()
        notifyDataSetChanged()
    }

    open fun isSelectMode(): Boolean = selectMode

    open fun toggleSelection(siteKey: String) {
        if (selectedKeys.contains(siteKey)) selectedKeys.remove(siteKey) else selectedKeys.add(siteKey)
        notifyDataSetChanged()
        onSelectionChanged?.run()
    }

    open fun selectAll() {
        for (item in items) selectedKeys.add(item.siteKey)
        notifyDataSetChanged()
    }

    open fun deselectAll() {
        selectedKeys.clear()
        notifyDataSetChanged()
    }

    open fun getSelectedKeys(): MutableSet<String> = HashSet(selectedKeys)

    open fun getSelectedCount(): Int = selectedKeys.size

    open fun refresh() {
        items.clear()
        val sites = ArrayList(VodConfig.get().getSites().filter { !it.isHide() })
        // Load once and reuse for ranking display.
        val perfMap = SiteRanker.get().loadPerformanceData(sites)
        val ranked =
            when (sortMode) {
                SORT_RELIABILITY -> SiteRanker.get().rankByReliability(sites)
                SORT_SPEED -> SiteRanker.get().rankBySpeed(sites)
                else -> SiteRanker.get().rankByPerformance(sites)
            }
        for ((index, site) in ranked.withIndex()) items.add(RankingItem(site.key, site.name, perfMap[site.key], index + 1))
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val holder = ViewHolder(AdapterSiteRankingBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)
        val params = holder.binding.root.layoutParams
        if (params != null) {
            params.height = ResUtil.getSiteRankingRowHeight()
            holder.binding.root.layoutParams = params
        }
        TextScale.bind(holder.binding.rank)
        TextScale.bind(holder.binding.name)
        TextScale.bind(holder.binding.score)
        TextScale.bind(holder.binding.stats)
        val padding = ResUtil.getSiteRankingPadding()
        val verticalPadding = LayoutConfig.getRankingVerticalPaddingPixels(padding)
        holder.binding.root.setPadding(padding, verticalPadding, padding, verticalPadding)
        return holder
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.bind(items[position])
    }

    private fun getScoreColor(score: Double): Int =
        ResUtil.getColor(
            when {
                score >= 0.7 -> R.color.ranking_green
                score >= 0.4 -> R.color.ranking_yellow
                else -> R.color.ranking_error
            },
        )

    open class ViewHolder(
        val binding: AdapterSiteRankingBinding,
        private val adapter: SiteRankingAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        private val originalBackground = binding.root.background
        private var item: RankingItem? = null

        init {
            binding.root.setOnClickListener {
                val current = item
                if (current != null) {
                    if (adapter.selectMode) {
                        adapter.toggleSelection(
                            current.siteKey,
                        )
                    } else {
                        adapter.clickListener?.onItemClick(current.siteKey)
                    }
                }
            }
            binding.root.setOnKeyListener { v, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        v.setTag(R.id.tag_click_consumed, true)
                        v.performClick()
                        return@setOnKeyListener true
                    } else if (event.action == KeyEvent.ACTION_DOWN) {
                        v.setTag(R.id.tag_click_consumed, null)
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }

        open fun bind(item: RankingItem) {
            this.item = item
            binding.rank.text = item.rank.toString()
            binding.rank.setTextColor(ResUtil.getColor(if (item.rank <= 3) R.color.ranking_gold else R.color.white))
            binding.name.text = item.siteName
            if (adapter.selectedKeys.contains(item.siteKey)) {
                binding.root.setBackgroundColor(ResUtil.getColor(R.color.ranking_blue_transparent))
            } else {
                binding.root.background =
                    originalBackground
            }
            val perf = item.performance
            if (perf != null && perf.totalRequests >= MIN_REQUESTS) {
                val score: Double
                val statsText: String
                when (adapter.sortMode) {
                    SORT_RELIABILITY -> {
                        score = perf.reliabilityScore
                        statsText =
                            String.format(Locale.getDefault(), "%.0f%% success", perf.successRate)
                    }

                    SORT_SPEED -> {
                        score = Math.min(1.0, perf.avgSpeed / MAX_DISPLAY_SPEED_BYTES)
                        statsText =
                            perf.formattedSpeed
                    }

                    else -> {
                        score = perf.performanceScore
                        statsText =
                            String.format(Locale.getDefault(), "%d/%d", perf.successCount, perf.totalRequests)
                    }
                }
                binding.score.text = String.format(Locale.getDefault(), "%.0f%%", score * 100)
                binding.score.setTextColor(adapter.getScoreColor(score))
                binding.stats.text = statsText
            } else {
                binding.score.text = "N/A"
                binding.score.setTextColor(ResUtil.getColor(R.color.white))
                binding.stats.text = ""
            }
        }
    }
}
