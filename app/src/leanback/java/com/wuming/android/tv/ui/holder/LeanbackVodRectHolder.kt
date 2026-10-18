package com.wuming.android.tv.ui.holder

import com.wuming.android.tv.utils.TextScale

import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.LeanbackProduct
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.databinding.AdapterVodRectBinding
import com.wuming.android.tv.ui.base.BaseVodHolder
import com.wuming.android.tv.ui.base.VodClickListener
import com.wuming.android.tv.utils.ImgUtil
import com.wuming.android.tv.utils.SearchHelper
import java.lang.ref.WeakReference

open class LeanbackVodRectHolder(
    private val binding: AdapterVodRectBinding,
    listener: VodClickListener?,
) : BaseVodHolder(binding.root) {
    private val listenerRef = WeakReference(listener)
    private var item: Vod? = null
    private var keyword: String? = null

    open fun setKeyword(keyword: String?) {
        this.keyword = keyword
    }

    init {
        TextScale.bind(binding.name)
        TextScale.bind(binding.year)
        TextScale.bind(binding.site)
        TextScale.bind(binding.remark)
        binding.root.setOnClickListener {
            val vod = item
            val listener = listenerRef.get()
            if (vod != null &&
                listener != null
            ) {
                listener.onItemClick(vod)
            }
        }
        binding.root.setOnLongClickListener {
            val vod = item
            val listener = listenerRef.get()
            if (vod != null &&
                listener != null
            ) {
                listener.onLongClick(vod)
            } else {
                false
            }
        }
    }

    open fun size(size: IntArray?): LeanbackVodRectHolder {
        if (size == null || size.isEmpty()) return this
        val root = binding.root
        val params =
            root.layoutParams ?: ViewGroup.LayoutParams(size[0], if (size.size > 1) size[1] else ViewGroup.LayoutParams.WRAP_CONTENT)
        if (root.layoutParams != null) {
            params.width = size[0]
            if (size.size > 1 && size[1] > 0) params.height = size[1]
        }
        root.layoutParams = params
        if (size.size > 3 &&
            size[3] > 0
        ) {
            val imageParams = binding.image.layoutParams
            imageParams.height = size[3]
            binding.image.layoutParams =
                imageParams
        }
        return this
    }

    override fun initView(item: Vod) {
        this.item = item
        val keyword = this.keyword
        if (!keyword.isNullOrEmpty()) {
            val highlighted = SearchHelper.highlightHtml(item.getSearchDisplayName(), keyword)
            if (highlighted.contains("<b>")) {
                val plain = highlighted.replace("<b>", "").replace("</b>", "")
                val spannable = SpannableString(plain)
                val color = ContextCompat.getColor(binding.name.context, R.color.highlight)
                applyHighlightSpans(spannable, highlighted, plain, color)
                binding.name.text = spannable
            } else {
                binding.name.text = item.getSearchDisplayName()
            }
        } else {
            binding.name.text = item.getSearchDisplayName()
        }

        binding.year.text = item.year
        binding.site.text = item.getSiteName()
        binding.remark.text = item.remarks
        binding.site.visibility = item.getSiteVisible()
        binding.year.visibility = item.getYearVisible()
        binding.remark.visibility = item.getRemarkVisible()
        binding.name.visibility = item.getNameVisible()
        ImgUtil.load(item.name, item.pic, binding.image)
    }

    override fun unbind() {
        ImgUtil.clearView(binding.image)
        item = null
        keyword = null
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
}
