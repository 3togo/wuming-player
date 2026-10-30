package com.wuming.android.tv.ui.holder

import androidx.core.content.ContextCompat
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.bean.VodShortName
import com.wuming.android.tv.databinding.AdapterVodListBinding
import com.wuming.android.tv.ui.base.BaseVodHolder
import com.wuming.android.tv.ui.base.VodClickListener
import com.wuming.android.tv.utils.ImgUtil
import com.wuming.android.tv.utils.TextScale

open class VodListHolder(
    private val binding: AdapterVodListBinding,
    listener: VodClickListener?,
) : BaseVodHolder(binding.root) {
    private val clicks = VodCardClicks(binding.root, listener)

    init {
        TextScale.bind(binding.name)
        TextScale.bind(binding.remark)
    }

    override fun initView(item: Vod) {
        clicks.item = item
        binding.name.text = VodShortName.lookup(item.name)

        binding.remark.text = item.remarks
        binding.remark.setTextColor(ContextCompat.getColor(binding.remark.context, remarkColorResource(item)))
        binding.remark.visibility = item.getRemarkVisible()
        binding.image.setPadding(0, 0, 0, 0)
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

    companion object {
        @JvmStatic fun remarkColorResource(item: Vod): Int {
            if (item.id.startsWith("opendrive-provider:")) {
                if (item.isFolder()) return R.color.cloud_login_connected
                if (item.getAction().startsWith("OpenDriveLogin:")) return R.color.cloud_login_disconnected
            }
            if (item.id.startsWith("opendrive-logout:")) return R.color.cloud_login_disconnected
            return R.color.white_70
        }
    }
}
