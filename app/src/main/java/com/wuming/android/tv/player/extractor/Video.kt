package com.wuming.android.tv.player.extractor

import android.net.Uri
import com.wuming.android.tv.player.Source
import com.wuming.android.tv.utils.UrlUtil

class Video : Source.Extractor {
    override fun match(uri: Uri): Boolean = "video" == UrlUtil.scheme(uri)

    @Throws(Exception::class)
    override fun fetch(url: String): String = url.substring(8)

    override fun stop() {}

    override fun exit() {}
}
