package com.wuming.android.tv.ui.adapter

import android.view.ViewGroup
import com.wuming.android.tv.databinding.AdapterVodBinding

/** Shared poster dimensions for mobile history and favorites grids. */
internal class VodGridLayout {
    private var width = 0
    private var height = 0
    private var margin = 0

    fun setSize(size: IntArray) {
        width = size[0]
        // adapter_vod uses the image-only height at index 3.
        height = size[3]
        margin = if (size.size > 2) size[2] else 0
    }

    fun apply(binding: AdapterVodBinding) {
        val params = binding.root.layoutParams as ViewGroup.MarginLayoutParams
        params.width = width
        params.setMargins(margin, margin, margin, margin)
        binding.root.layoutParams = params
        binding.image.layoutParams.height = height
    }
}
