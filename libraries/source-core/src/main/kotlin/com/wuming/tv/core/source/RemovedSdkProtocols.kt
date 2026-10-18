package com.wuming.tv.core.source

import java.util.Locale

/** Protocols that required the SDK engines removed from the public distribution. */
object RemovedSdkProtocols {
    @JvmStatic
    fun matches(value: String?): Boolean {
        val raw = value?.trim().orEmpty()
        val scheme = raw.substringBefore(':', "").lowercase(Locale.ROOT)
        if (scheme in setOf("ftp", "magnet", "ed2k", "thunder", "tvbus", "mitv",
                "jianpian", "tvbox-xg", "xg", "xgplay") || scheme.matches(Regex("p[2-9]p"))) return true
        // Torrent URLs formerly invoked the removed native episode-expansion path.
        return raw.substringBefore('|').substringBefore(';').substringBefore('?')
            .substringBefore('#').endsWith(".torrent", ignoreCase = true)
    }
}
