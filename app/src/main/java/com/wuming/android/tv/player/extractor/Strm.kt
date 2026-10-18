package com.wuming.android.tv.player.extractor

import android.net.Uri
import com.wuming.android.tv.player.Source
import com.wuming.android.tv.utils.UrlUtil
import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Path
import com.google.common.net.HttpHeaders
import java.io.File

class Strm : Source.Extractor {
    override fun match(uri: Uri): Boolean = UrlUtil.path(uri).endsWith(".strm")

    @Throws(Exception::class)
    override fun fetch(url: String): String {
        if (UrlUtil.isHttp(url)) return http(url)
        var fileUrl = url
        if (fileUrl.startsWith("file")) fileUrl = fileUrl.substring(7)
        return Path.read(File(fileUrl)).split("\\R".toRegex(), 2)[0]
    }

    @Throws(Exception::class)
    private fun http(url: String): String {
        OkHttp.newCall(OkHttp.noRedirect(), url).execute().use { res ->
            val content = res.header(HttpHeaders.CONTENT_DISPOSITION).orEmpty()
            val text = content.contains(".strm") || content.contains(".txt")
            if (!text) return url
            return res.body.string().split("\\R".toRegex(), 2)[0]
        }
    }

    override fun stop() {}

    override fun exit() {}
}
