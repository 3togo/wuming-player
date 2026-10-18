package com.wuming.android.tv.bean

import com.wuming.tv.core.media.MediaFileClassifier

object DriveType {
    enum class TYPE {
        LOCAL,
        WEBDAV,
        ALISTWEB
    }

    @JvmStatic
    fun getTypeNames(): Array<String> = arrayOf("本地目录", "Webdav", "Alist网页")

    @JvmStatic
    fun isVideoType(type: String?): Boolean = MediaFileClassifier.isVideoExtension(type)
}
