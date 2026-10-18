package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.SiteRanker

/** Fast-search site lists with platform-specific sizing and focus hooks. */
abstract class BaseFastListAdapter : RecyclerView.Adapter<BaseFastListAdapter.ViewHolder>() {
    private val items = ArrayList<String>()
    private val siteKeys = ArrayList<String?>()
    private var listener: OnItemClickListener? = null
    private var lostFocusTimestamp = 0L
    private var focusStep = 0
    private var focusView: View? = null

    @JvmField protected var siteListLayout = 0

    fun interface OnItemClickListener {
        fun onItemClick(item: String)
    }

    open fun setOnItemClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    open fun setSiteListLayout(layout: Int) {
        siteListLayout = layout
    }

    open fun setNewData(data: List<String>?) {
        items.clear()
        siteKeys.clear()
        if (data != null) items.addAll(data)
        notifyDataSetChanged()
    }

    open fun addData(item: String) {
        addData(item, null)
    }

    open fun addData(
        item: String,
        siteKey: String?,
    ) {
        items.add(item)
        siteKeys.add(siteKey)
        notifyItemInserted(items.size - 1)
    }

    open fun addData(
        position: Int,
        item: String,
    ) {
        addData(position, item, null)
    }

    open fun addData(
        position: Int,
        item: String,
        siteKey: String?,
    ) {
        items.add(position, item)
        siteKeys.add(position, siteKey)
        notifyItemInserted(position)
    }

    open fun getData(): MutableList<String> = items

    open fun reset() {
        lostFocusTimestamp = 0
        focusStep = 0
        focusView = null
    }

    open fun onLostFocus(child: View) {
        if (lostFocusTimestamp == 0L) lostFocusTimestamp = System.currentTimeMillis()
    }

    open fun onSetFocus(child: View): Int {
        if (System.currentTimeMillis() - lostFocusTimestamp > 200) focusStep = 0
        val index = (child.parent as ViewGroup).indexOfChild(child)
        val previous = focusView
        if (previous != null) {
            val previousIndex = (previous.parent as ViewGroup).indexOfChild(previous)
            if (Math.abs(index - previousIndex) > focusStep) {
                focusStep = 0
                val offset = if (index > previousIndex) -1 else 1
                (previous.parent as ViewGroup).getChildAt(index + offset).requestFocus()
                return -1
            }
        }
        lostFocusTimestamp = 0
        focusView = child
        focusStep = 1
        return 1
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val layout =
            when (siteListLayout) {
                2 -> R.layout.item_fast_search_site_compact
                1 -> R.layout.item_fast_search_site_horizontal
                else -> R.layout.item_fast_search_site
            }
        val holder = ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false), listener)
        configureHolder(holder, parent)
        return holder
    }

    protected open fun configureHolder(
        holder: ViewHolder,
        parent: ViewGroup,
    ) {}

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = items[position]
        val siteKey = if (position < siteKeys.size) siteKeys[position] else null
        var displayText = item
        if (!siteKey.isNullOrEmpty()) {
            val info = SiteRanker.get().getRankingInfo(siteKey)
            if (info.hasData) displayText = String.format(java.util.Locale.getDefault(), "%s (%.0f%%)", item, info.performanceScore * 100)
        }
        holder.tvSearchSite.text = displayText
        configureBoundHolder(holder)
        holder.bind(item)
    }

    protected open fun configureBoundHolder(holder: ViewHolder) {}

    override fun getItemCount(): Int = items.size

    open class ViewHolder(
        itemView: View,
        listener: OnItemClickListener?,
    ) : RecyclerView.ViewHolder(itemView) {
        @JvmField val tvSearchSite: TextView = itemView.findViewById(R.id.tvSearchSite)
        private var item: String? = null

        init {
            itemView.setOnClickListener { item?.let { listener?.onItemClick(it) } }
        }

        open fun bind(item: String) {
            this.item = item
        }
    }
}
