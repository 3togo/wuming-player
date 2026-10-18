package com.wuming.android.tv.ui.adapter

import com.wuming.android.tv.utils.TextScale

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.MobileProduct
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.SearchFilter
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.databinding.AdapterSearchBinding
import com.wuming.android.tv.search.CollectionWorkflow
import com.wuming.android.tv.utils.ImgUtil
import com.wuming.android.tv.utils.PosterTitle
import com.wuming.android.tv.utils.SearchHelper
import com.wuming.android.tv.utils.VideoRankManager
import java.util.ArrayList

@JvmSuppressWildcards
open class SearchAdapter(
    context: Context?,
    private val listener: OnClickListener,
) : BaseDiffAdapter<Vod, SearchAdapter.ViewHolder>() {
    private val context = context?.applicationContext
    private var size = if (context != null) MobileProduct.getCollectSpec(context) else intArrayOf(300, 400)
    private var keyword: String? = null
    private var filter: SearchFilter? = SearchFilter()
    private var allItems: List<Vod> = ArrayList()

    open fun setSize(size: IntArray?) {
        if (size != null && size.size >= 2) {
            this.size = size
            notifyItemRangeChanged(0, itemCount)
        }
    }

    open fun setKeyword(keyword: String?) {
        this.keyword = keyword
    }

    open fun setFilter(filter: SearchFilter?) {
        this.filter = filter
        applyFilterAndSort()
    }

    open fun getFilter(): SearchFilter? = filter

    override fun setItems(items: List<Vod>?) {
        setItems(items, Runnable {})
    }

    override fun setItems(
        items: List<Vod>?,
        runnable: Runnable?,
    ) {
        allItems = CollectionWorkflow.mergeCandidates(emptyList(), VideoRankManager.apply(items))
        applyFilterAndSort(runnable)
    }

    override fun setItems(
        items: List<Vod>?,
        callback: Callback,
    ) {
        setItems(items, Runnable { callback.onUpdateFinished(true) })
    }

    override fun addAll(items: List<Vod>) {
        addAll(items, Runnable {})
    }

    override fun addAll(
        items: List<Vod>,
        runnable: Runnable?,
    ) {
        allItems = CollectionWorkflow.mergeCandidates(allItems, VideoRankManager.apply(items))
        applyFilterAndSort(runnable)
    }

    private fun applyFilterAndSort() {
        applyFilterAndSort(Runnable {})
    }

    private fun applyFilterAndSort(committed: Runnable?) {
        super.setItems(CollectionWorkflow.filterAndSort(allItems, filter!!, keyword!!), committed)
    }

    fun interface OnClickListener {
        fun onItemClick(item: Vod)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterSearchBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)

    private fun getSpanCount(parent: ViewGroup): Int {
        if (parent is RecyclerView) {
            val lm = parent.layoutManager
            if (lm is androidx.recyclerview.widget.GridLayoutManager) return lm.spanCount
        }
        return 1
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = getItem(position)

        TextScale.bind(holder.binding.name)
        TextScale.bind(holder.binding.site)
        TextScale.bind(holder.binding.remark)
        val lp = holder.binding.root.layoutParams as? ViewGroup.MarginLayoutParams ?: ViewGroup.MarginLayoutParams(size[0], size[1])
        lp.width = size[0]
        lp.height = size[1]
        if (size.size > 2 && size[2] > 0) {
            val m = size[2]
            lp.setMargins(m, m, m, m)
        }
        holder.binding.root.layoutParams = lp
        if (size.size > 3 &&
            size[3] > 0
        ) {
            val imageParams = holder.binding.image.layoutParams
            imageParams.height = size[3]
            holder.binding.image.layoutParams =
                imageParams
        }
        val keyword = this.keyword
        if (!keyword.isNullOrEmpty()) {
            val highlighted = SearchHelper.highlightHtml(item.getSearchDisplayName(), keyword)
            if (highlighted.contains("<b>")) {
                val plain = highlighted.replace("<b>", "").replace("</b>", "")
                val spannable = SpannableString(plain)
                val color = ContextCompat.getColor(holder.binding.name.context, R.color.highlight)
                applyHighlightSpans(spannable, highlighted, plain, color)
                holder.binding.name.text = spannable
            } else {
                holder.binding.name.text = item.getSearchDisplayName()
            }
        } else {
            holder.binding.name.text = item.getSearchDisplayName()
        }
        PosterTitle.wrapAtSpace(holder.binding.name)
        holder.binding.site.text = item.getSiteName()
        holder.binding.remark.text = item.remarks
        holder.binding.site.visibility = item.getSiteVisible()
        holder.binding.remark.visibility = item.getRemarkVisible()
        holder.setItem(item)
        ImgUtil.load(item.name, item.pic, holder.binding.image)
    }

    private fun applyHighlightSpans(
        spannable: SpannableString,
        highlighted: String,
        plain: String,
        color: Int,
    ) {
        var plainIdx = 0
        var i = 0
        while (i < highlighted.length) {
            if (highlighted.startsWith("<b>", i)) {
                i += 3
                val start = plainIdx
                while (i < highlighted.length && !highlighted.startsWith("</b>", i)) {
                    i++
                    plainIdx++
                }
                val end = plainIdx
                spannable.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i += 4
            } else {
                i++
                plainIdx++
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        ImgUtil.clearView(holder.binding.image)
    }

    open class ViewHolder(
        val binding: AdapterSearchBinding,
        adapter: SearchAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Vod? = null

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                val item = this.item
                if (pos != RecyclerView.NO_POSITION &&
                    item != null
                ) {
                    adapter.listener.onItemClick(item)
                }
            }
        }

        open fun setItem(item: Vod?) {
            this.item = item
        }
    }
}
