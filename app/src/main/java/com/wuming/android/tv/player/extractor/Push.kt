package com.wuming.android.tv.player.extractor

import android.app.Activity
import android.net.Uri
import android.os.SystemClock
import com.wuming.android.tv.App
import com.wuming.android.tv.player.Source
import com.wuming.android.tv.ui.activity.VideoActivity
import com.wuming.android.tv.utils.UrlUtil

class Push : Source.Extractor {
    override fun match(uri: Uri): Boolean = "push" == UrlUtil.scheme(uri)

    @Throws(Exception::class)
    override fun fetch(url: String): String {
        val activity: Activity? = App.activeActivity()
        val target = target(url)
        if (activity != null && target.isNotEmpty()) {
            VideoActivity.start(activity, target)
            SystemClock.sleep(500)
        }
        return ""
    }

    companion object {
        @JvmStatic
        fun target(url: String?): String {
            if (url == null || !url.startsWith("push://") || url.length == 7) return ""
            return url.substring(7)
        }
    }

    override fun stop() {}

    override fun exit() {}
}
