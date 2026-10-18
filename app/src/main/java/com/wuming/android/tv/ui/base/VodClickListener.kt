package com.wuming.android.tv.ui.base

import com.wuming.android.tv.bean.Vod

interface VodClickListener {
    fun onItemClick(item: Vod?)
    fun onLongClick(item: Vod?): Boolean
}
