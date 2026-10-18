package com.wuming.android.tv.utils

import com.wuming.android.tv.BuildConfig

object Github {
    @JvmStatic
    fun getUpdateFeeds(): List<String> = listOf(
        "https://raw.githubusercontent.com/${BuildConfig.UPDATE_REPOSITORY}/main/channels/${BuildConfig.BUILD_TYPE}.json",
    )
}
