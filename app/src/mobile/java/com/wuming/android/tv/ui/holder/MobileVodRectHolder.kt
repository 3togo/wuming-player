package com.wuming.android.tv.ui.holder

import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.databinding.AdapterVodRectBinding
import com.wuming.android.tv.ui.base.BaseVodHolder
import com.wuming.android.tv.ui.base.VodClickListener
import com.wuming.android.tv.utils.ImgUtil
import com.wuming.android.tv.utils.PosterTitle
import com.wuming.android.tv.utils.TextScale

open class MobileVodRectHolder(
    private val binding: AdapterVodRectBinding,
    listener: VodClickListener?,
) : BaseVodHolder(binding.root) {
    private val clicks = VodCardClicks(binding.root, listener)

    init {
        TextScale.bind(binding.name)
        TextScale.bind(binding.year)
        TextScale.bind(binding.site)
        TextScale.bind(binding.remark)
    }

    open fun size(size: IntArray?): MobileVodRectHolder {
        VodCardLayout.apply(binding.root, binding.image, size)
        return this
    }

    override fun initView(item: Vod) {
        clicks.item = item
        binding.name.text = item.getSearchDisplayName()
        PosterTitle.wrapAtSpace(binding.name)
        binding.year.text = item.year
        binding.site.text = item.getSiteName()
        binding.remark.text = item.remarks
        binding.site.visibility = item.getSiteVisible()
        binding.year.visibility = item.getYearVisible()
        binding.remark.visibility = item.getRemarkVisible()
        binding.name.visibility = item.getNameVisible()
        if (item.isFolder()) {
            ImgUtil.loadFit(item.name, item.pic, binding.image)
        } else {
            ImgUtil.load(item.name, item.pic, binding.image)
        }
    }

    override fun unbind() {
        ImgUtil.clearView(binding.image)
        clicks.item = null
    }
}
