package com.wuming.android.tv.ui.activity

import com.wuming.android.tv.R
import com.wuming.android.tv.ui.base.BaseFileActivity
import com.wuming.android.tv.utils.ResUtil

class FileActivity : BaseFileActivity() {
    override fun configureBrowser() {
        mBinding.recycler.setVerticalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_16))
    }

    override fun resetBrowserPosition() {
        mBinding.recycler.setSelectedPosition(0)
    }
}
