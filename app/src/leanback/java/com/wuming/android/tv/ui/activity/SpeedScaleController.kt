package com.wuming.android.tv.ui.activity

import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.ActivityVideoBinding
import com.wuming.android.tv.utils.ResUtil

open class SpeedScaleController(
    private val mHost: Host,
    binding: ActivityVideoBinding,
) : BaseSpeedScaleController(mHost, binding) {
    interface Host : BaseSpeedScaleController.Host {
        fun getScale(): Int

        fun setScale(scale: Int)
    }

    open fun onScale() {
        val index = mHost.getScale()
        val array = ResUtil.getStringArray(R.array.select_scale)
        mHost.setScale(if (index == array.size - 1) 0 else index + 1)
    }
}
