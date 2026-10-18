package com.wuming.android.tv.ui.dialog

import android.content.Context
import com.wuming.android.tv.utils.DepotManager
import com.wuming.tv.core.source.config.OfflineLinkCatalog
import com.wuming.tv.core.source.config.ProtectedSourceUrl

/** Recognizes URLs shipped in the source browser when a config is opened later. */
internal object BundledSourceAccess {
    fun isBundled(context: Context, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (ProtectedSourceUrl.hasCredential(url)) return true
        val depot = runCatching { DepotManager.loadDepot(context) }.getOrNull()
        if (depot != null) for (kind in arrayOf("vod", "live")) {
            val rows = depot.optJSONArray(kind) ?: continue
            for (index in 0 until rows.length()) if (rows.optJSONObject(index)?.optString("url") == url) return true
        }
        return runCatching {
            context.assets.open("link_catalog.json").use { OfflineLinkCatalog.parse(it.readBytes()) }
                .any { group -> group.entries.any { it.url == url } }
        }.getOrDefault(false)
    }
}
