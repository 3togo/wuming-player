package com.wuming.android.tv.ui.activity

import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.ActivityVideoBinding
import com.wuming.android.tv.utils.ResUtil

open class SpeedScaleController(
    private val mHost: Host,
    binding: ActivityVideoBinding,
) : BaseSpeedScaleController(mHost, binding) {
    interface Host : BaseSpeedScaleController.Host {
        fun getScaleIndex(): Int

        fun getKeyDownScale(): Float

        fun resetKeyDownScale()

        fun setScale(scale: Int)

        fun setR1Callback()
    }

    open fun onScale() {
        val index = mHost.getScaleIndex()
        val array = ResUtil.getStringArray(R.array.select_scale)
        if (mHost.getKeyDownScale() != 1.0f) {
            mHost.resetKeyDownScale()
        } else {
            mHost.setScale(if (index == array.size - 1) 0 else index + 1)
        }
        mHost.setR1Callback()
    }

    override fun onInteraction() {
        mHost.setR1Callback()
    }

    open fun onScale(tag: Int) {
        mHost.resetKeyDownScale()
        mHost.setScale(tag)
    }
}
