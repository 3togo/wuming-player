package com.wuming.android.tv.ui.custom

import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.DiffCallback
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.ViewHolderTask
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.R

/** Updates the real collection-search rows without clearing their owning adapter. */
class CollectSearchGrid(
    private val grid: BaseGridView,
    private val rows: ArrayObjectAdapter,
    private val presenter: Presenter,
    private val columns: Int,
) {
    private var generation = 0L
    private var anchor: String? = null
    private val diff = object : DiffCallback<Vod>() {
        override fun areItemsTheSame(old: Vod, new: Vod) = GridFocusRestorer.vodKey(old) == GridFocusRestorer.vodKey(new)
        override fun areContentsTheSame(old: Vod, new: Vod) = old === new
    }

    fun remoteInput() { generation++; anchor = null }
    fun release() { remoteInput() }

    fun submit(items: List<Vod>) {
        val key = anchor ?: GridFocusRestorer.captureVodKey(grid)
        anchor = key
        val revision = ++generation
        grid.itemAnimator = null
        val parts = items.chunked(columns)
        parts.forEachIndexed { index, part ->
            if (index < rows.size()) {
                val row = rows.get(index) as ListRow
                (row.adapter as ArrayObjectAdapter).setItems(part, diff)
            } else {
                val children = ArrayObjectAdapter(presenter)
                children.addAll(0, part)
                rows.add(ListRow(children))
            }
        }
        if (rows.size() > parts.size) rows.removeItems(parts.size, rows.size() - parts.size)
        val position = items.indexOfFirst { GridFocusRestorer.vodKey(it) == key }
        if (key == null || position < 0) return
        // Selection alone does not establish D-pad focus. Wait for BOTH row and card
        // holders, then explicitly focus the card whose presenter bound this key.
        grid.setSelectedPosition(position / columns, object : ViewHolderTask {
            override fun run(holder: RecyclerView.ViewHolder) {
                if (revision != generation || !grid.isShown || !grid.hasWindowFocus()) return
                val bridge = holder as? ItemBridgeAdapter.ViewHolder ?: return
                val rowPresenter = bridge.presenter as? ListRowPresenter ?: return
                val row = rowPresenter.getRowViewHolder(bridge.viewHolder) as? ListRowPresenter.ViewHolder ?: return
                val inner = row.gridView
                inner.itemAnimator = null
                inner.setSelectedPosition(position % columns, object : ViewHolderTask {
                    override fun run(card: RecyclerView.ViewHolder) {
                        if (revision != generation || !grid.isShown || !grid.hasWindowFocus()) return
                        val boundView = (card as? ItemBridgeAdapter.ViewHolder)?.viewHolder?.view ?: card.itemView
                        if (boundView.getTag(R.id.tag_vod_focus_key) != key) return
                        boundView.requestFocus()
                    }
                })
            }
        })
    }
}
