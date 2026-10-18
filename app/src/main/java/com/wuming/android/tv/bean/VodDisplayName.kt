package com.wuming.android.tv.bean

object VodDisplayName {
    @JvmStatic
    fun format(value: String?): String = com.wuming.tv.core.catalog.VodDisplayName.format(value)
}
