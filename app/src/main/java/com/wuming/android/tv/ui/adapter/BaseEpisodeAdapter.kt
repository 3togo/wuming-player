package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.ui.base.BaseEpisodeHolder
import com.wuming.tv.core.library.EpisodeAdvancePolicy

/** Episode navigation, including display-name preparation before binding. */
@JvmSuppressWildcards
abstract class BaseEpisodeAdapter<VH : BaseEpisodeHolder> : RecyclerView.Adapter<VH> {
    @JvmField protected val mItems: MutableList<Episode>
    constructor() {
        mItems = ArrayList()
    }
    constructor(items: ArrayList<Episode>) {
        mItems = items
        prepareDisplayNames()
    }

    open fun addAll(items: List<Episode>) {
        mItems.clear()
        mItems.addAll(items)
        prepareDisplayNames()
        notifyDataSetChanged()
    }

    private fun prepareDisplayNames() {
        for (item in mItems) item.getDisplayName()
    }

    open fun clear() {
        mItems.clear()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = mItems.size

    open fun get(position: Int): Episode = mItems[position]

    open fun getItems(): MutableList<Episode> = mItems

    open fun isEmpty(): Boolean = mItems.isEmpty()

    open fun getPosition(): Int = mItems.indexOfFirst { it.isActivated() }

    open fun getPosition(item: Episode): Int = mItems.indexOf(item)

    open fun getActivated(): Episode = getPosition().let { if (it == -1) Episode() else mItems[it] }

    open fun getNext(): Episode? {
        val next = EpisodeAdvancePolicy.nextIndex(getPosition(), itemCount) ?: return null
        return mItems[next]
    }

    open fun getPrev(): Episode? {
        val previous = EpisodeAdvancePolicy.previousIndex(getPosition(), itemCount) ?: return null
        return mItems[previous]
    }

    abstract override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): VH

    abstract override fun onBindViewHolder(
        holder: VH,
        position: Int,
    )
}
