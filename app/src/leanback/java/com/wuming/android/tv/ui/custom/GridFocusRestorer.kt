package com.wuming.android.tv.ui.custom

import android.os.Bundle
import android.view.View
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowView
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Vod
import java.util.function.IntFunction

/** Restores a TV grid selection after a page change or fragment transition. */
object GridFocusRestorer {
    data class ItemFocus(val key: String, val fallbackPosition: Int)
    private data class VodLocation(val row: Int, val column: Int)
    private data class GridSelection(val row: Int, val column: Int, val focused: Boolean)

    @JvmStatic
    fun saveItemFocus(state: Bundle, prefix: String, focus: ItemFocus?) {
        if (focus == null) return
        state.putString("${prefix}Key", focus.key)
        state.putInt("${prefix}Position", focus.fallbackPosition)
    }

    @JvmStatic
    fun readItemFocus(state: Bundle?, prefix: String): ItemFocus? {
        val key = state?.getString("${prefix}Key") ?: return null
        return ItemFocus(key, state.getInt("${prefix}Position"))
    }

    @JvmStatic
    fun vodKey(vod: Vod): String =
        if (vod.id.isNotEmpty()) "${vod.siteKey}\u0000${vod.id}" else "${vod.siteKey}\u0000name:${vod.name}"

    @JvmStatic
    fun captureVodKey(grid: BaseGridView): String? {
        var current: View? = grid.findFocus()
        while (current != null && current !== grid) {
            (current.getTag(R.id.tag_vod_focus_key) as? String)?.let { return it }
            current = current.parent as? View
        }
        return null
    }

    @JvmStatic
    fun restoreVodAfterLayout(
        grid: BaseGridView,
        adapter: ArrayObjectAdapter,
        key: String?,
        fallbackPosition: Int,
        onlyIfFocusAvailable: Boolean,
    ) {
        val initialSelection = selection(grid)
        val gridAdapter = grid.adapter
        grid.post outer@{
            if (!grid.isAttachedToWindow || !grid.isShown || grid.adapter !== gridAdapter) return@outer
            if (onlyIfFocusAvailable && hasVisibleFocusOutside(grid)) return@outer
            if (selectionMoved(grid, initialSelection)) return@outer
            val location = key?.let { findVodLocation(adapter, it) }
            if (location == null) {
                restore(grid, fallbackPosition)
                return@outer
            }
            grid.setSelectedPosition(location.row)
            val rowSelection = selection(grid)
            grid.post inner@{
                if (!grid.isAttachedToWindow || !grid.isShown || grid.adapter !== gridAdapter) return@inner
                if (onlyIfFocusAvailable && hasVisibleFocusOutside(grid)) return@inner
                if (selectionMoved(grid, rowSelection)) return@inner
                if (findVodLocation(adapter, key) != location) return@inner
                val row = grid.findViewHolderForAdapterPosition(location.row)?.itemView as? ListRowView
                val innerGrid = row?.gridView
                if (location.column >= 0 && innerGrid != null &&
                    location.column < (innerGrid.adapter?.itemCount ?: 0)
                ) {
                    innerGrid.setSelectedPosition(location.column)
                    innerGrid.requestFocus()
                } else {
                    grid.requestFocus()
                }
            }
        }
    }

    private fun findVodLocation(adapter: ArrayObjectAdapter, key: String): VodLocation? {
        for (row in 0 until adapter.size()) {
            when (val item = adapter.get(row)) {
                is Vod -> if (vodKey(item) == key) return VodLocation(row, -1)
                is ListRow -> {
                    val children = item.adapter
                    for (column in 0 until children.size()) {
                        val vod = children.get(column) as? Vod ?: continue
                        if (vodKey(vod) == key) return VodLocation(row, column)
                    }
                }
            }
        }
        return null
    }

    @JvmStatic
    fun captureItem(grid: RecyclerView, keyAt: IntFunction<String>): ItemFocus? {
        val focused = grid.findFocus() ?: return null
        val holder = grid.findContainingViewHolder(focused) ?: return null
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION || position >= (grid.adapter?.itemCount ?: 0)) return null
        return ItemFocus(keyAt.apply(position), position)
    }

    @JvmStatic
    fun restoreItemAfterLayout(grid: RecyclerView, focus: ItemFocus?, keyAt: IntFunction<String>) {
        if (focus == null) return
        val initialPosition = focusedPosition(grid)
        val adapter = grid.adapter ?: return
        grid.post outer@{
            val count = grid.adapter?.itemCount ?: 0
            if (!grid.isAttachedToWindow || !grid.isShown || grid.adapter !== adapter || count == 0 ||
                hasVisibleFocusOutside(grid)) return@outer
            if (focusedItemMoved(grid, initialPosition)) return@outer
            val position = (0 until count).firstOrNull { keyAt.apply(it) == focus.key }
                ?: focus.fallbackPosition.coerceIn(0, count - 1)
            val targetKey = keyAt.apply(position)
            grid.scrollToPosition(position)
            val scrolledPosition = focusedPosition(grid)
            grid.post inner@{
                if (!grid.isAttachedToWindow || !grid.isShown || grid.adapter !== adapter ||
                    hasVisibleFocusOutside(grid)) return@inner
                if (focusedItemMoved(grid, scrolledPosition)) return@inner
                // AsyncListDiffer may replace or reorder the list before this callback runs.
                if (position >= (grid.adapter?.itemCount ?: 0) || keyAt.apply(position) != targetKey) return@inner
                grid.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
    }

    @JvmStatic
    fun restoreAfterLayout(grid: BaseGridView, position: Int) {
        val initialSelection = selection(grid)
        grid.post {
            if (!selectionMoved(grid, initialSelection)) restore(grid, position)
        }
    }

    /** Take focus only if hiding the previous content left no visible focus target. */
    @JvmStatic
    fun restoreIfFocusLost(grid: BaseGridView, position: Int) {
        grid.post {
            val focused: View? = grid.rootView.findFocus()
            if (focused == null || focused === grid || !focused.isShown) restore(grid, position)
        }
    }

    private fun restore(grid: BaseGridView, position: Int) {
        val count = grid.adapter?.itemCount ?: 0
        if (!grid.isAttachedToWindow || !grid.isShown || count == 0) return
        grid.setSelectedPosition(position.coerceIn(0, count - 1))
        grid.requestFocus()
    }

    private fun hasVisibleFocusOutside(grid: RecyclerView): Boolean {
        val focused = grid.rootView.findFocus() ?: return false
        if (!focused.isShown) return false
        var current: View? = focused
        while (current != null) {
            if (current === grid) return false
            current = current.parent as? View
        }
        return true
    }

    private fun focusedPosition(grid: RecyclerView): Int {
        val focused = grid.findFocus() ?: return RecyclerView.NO_POSITION
        return grid.findContainingViewHolder(focused)?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
    }

    private fun focusedItemMoved(grid: RecyclerView, original: Int): Boolean {
        val current = focusedPosition(grid)
        return original != RecyclerView.NO_POSITION && current != RecyclerView.NO_POSITION && current != original
    }

    private fun selection(grid: BaseGridView): GridSelection {
        val focused = grid.findFocus()
        var child: View? = focused
        while (child != null && child !== grid) {
            if (child is BaseGridView) return GridSelection(grid.selectedPosition, child.selectedPosition, true)
            child = child.parent as? View
        }
        return GridSelection(grid.selectedPosition, -1, grid.hasFocus())
    }

    private fun selectionMoved(grid: BaseGridView, original: GridSelection): Boolean {
        val current = selection(grid)
        if (!original.focused || !current.focused || original.row < 0) return false
        return current.row != original.row ||
            (original.column >= 0 && current.column >= 0 && current.column != original.column)
    }
}
