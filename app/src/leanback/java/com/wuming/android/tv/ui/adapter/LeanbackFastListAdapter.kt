package com.wuming.android.tv.ui.adapter

import com.wuming.android.tv.utils.TextScale

import android.view.ViewGroup
import android.view.View
import androidx.core.view.doOnNextLayout
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.ResUtil

open class LeanbackFastListAdapter : BaseFastListAdapter() {
    private val ids = HashMap<String, Long>()
    init { setHasStableIds(true) }
    override fun getItemId(position: Int): Long = ids.getOrPut(getData()[position]) { ids.size.toLong() }

    // The old child-index heuristic confuses layout moves with D-pad navigation.
    override fun onSetFocus(child: View): Int = 1
    override fun onLostFocus(child: View) {}

    fun rankSources(
        grid: HorizontalGridView,
        position: java.util.function.ToIntFunction<String>,
        stillIdle: java.util.function.BooleanSupplier,
    ) {
        val focused = grid.findFocus()?.let { grid.findContainingViewHolder(it)?.itemId }

        // The All chip remains first; move notifications preserve the focused chip.
        val ordered = getData().drop(1).sortedBy { position.applyAsInt(it) }
        ordered.forEachIndexed { index, name ->
            val from = getData().indexOf(name)
            if (from != index + 1) moveItem(from, index + 1)
        }
        if (focused == null) return
        val target = getData().indices.firstOrNull { getItemId(it) == focused } ?: return
        // Leanback tracks its selected position separately from RecyclerView's focus.
        grid.selectedPosition = target
        grid.doOnNextLayout {
            if (stillIdle.asBoolean && grid.hasWindowFocus()) {
                grid.findViewHolderForItemId(focused)?.itemView?.requestFocus()
            }
        }
    }

    private var targetItemHeight = 0

    open fun setTargetItemHeight(height: Int) {
        targetItemHeight = height
    }

    override fun configureHolder(
        holder: ViewHolder,
        parent: ViewGroup,
    ) {
        if (siteListLayout == 1 || siteListLayout == 2) {
            LayoutConfig.styleTvSearchFilterChip(holder.tvSearchSite, targetItemHeight)
        } else {
            TextScale.bind(holder.tvSearchSite)
        }
    }
}
