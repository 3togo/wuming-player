package com.wuming.android.tv.impl

import com.wuming.android.tv.bean.Value

interface FilterCallback {
    fun setFilter(key: String?, value: Value?)
}
