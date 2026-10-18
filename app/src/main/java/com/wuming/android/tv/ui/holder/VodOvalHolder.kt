package com.wuming.android.tv.ui.holder

import com.wuming.android.tv.utils.TextScale

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.bean.VodShortName
import com.wuming.android.tv.databinding.AdapterVodOvalBinding
import com.wuming.android.tv.ui.base.BaseVodHolder
import com.wuming.android.tv.ui.base.VodClickListener
import com.wuming.android.tv.utils.ImgUtil
import com.wuming.android.tv.utils.PosterTitle
import java.lang.ref.WeakReference

open class VodOvalHolder(
    private val binding: AdapterVodOvalBinding,
    listener: VodClickListener?,
) : BaseVodHolder(binding.root) {
    private val listenerRef = WeakReference(listener)
    private var item: Vod? = null

    init {
        TextScale.bind(binding.name)
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

    open fun size(size: IntArray?): VodOvalHolder {
        if (size == null || size.isEmpty()) return this
        val root = binding.root
        val params =
            root.layoutParams as? ViewGroup.MarginLayoutParams
                ?: ViewGroup.MarginLayoutParams(size[0], if (size.size > 1) size[1] else ViewGroup.LayoutParams.WRAP_CONTENT)
        params.width = size[0]
        if (size.size > 1 && size[1] > 0) params.height = size[1]
        if (size.size > 2 && size[2] > 0) {
            val m = size[2]
            params.setMargins(m, m, m, m)
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
        binding.name.text = VodShortName.lookup(item.name)
        PosterTitle.wrapAtSpace(binding.name)

        binding.name.visibility = item.getNameVisible()
        ImgUtil.load(item.name, item.pic, binding.image)
    }

    override fun unbind() {
        ImgUtil.clearView(binding.image)
        item = null
    }
}
