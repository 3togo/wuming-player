package com.wuming.android.tv.ui.holder

import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.bean.VodShortName
import com.wuming.android.tv.databinding.AdapterVodOvalBinding
import com.wuming.android.tv.ui.base.BaseVodHolder
import com.wuming.android.tv.ui.base.VodClickListener
import com.wuming.android.tv.utils.ImgUtil
import com.wuming.android.tv.utils.PosterTitle
import com.wuming.android.tv.utils.TextScale

open class VodOvalHolder(
    private val binding: AdapterVodOvalBinding,
    listener: VodClickListener?,
) : BaseVodHolder(binding.root) {
    private val clicks = VodCardClicks(binding.root, listener)

    init {
        TextScale.bind(binding.name)
    }

    open fun size(size: IntArray?): VodOvalHolder {
        VodCardLayout.apply(binding.root, binding.image, size)
        return this
    }

    override fun initView(item: Vod) {
        clicks.item = item
        binding.name.text = VodShortName.lookup(item.name)
        PosterTitle.wrapAtSpace(binding.name)

        binding.name.visibility = item.getNameVisible()
        ImgUtil.load(item.name, item.pic, binding.image)
    }

    override fun unbind() {
        ImgUtil.clearView(binding.image)
        clicks.item = null
    }
}
