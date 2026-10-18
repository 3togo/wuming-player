package com.wuming.android.tv.server.process

object DesktopCatalogSessions {
    @JvmStatic fun issueItem(source: String?, providerId: String?) = com.wuming.tv.core.catalog.DesktopCatalogSessions.issueItem(source, providerId)
    @JvmStatic fun resolveItem(source: String?, handle: String?) = com.wuming.tv.core.catalog.DesktopCatalogSessions.resolveItem(source, handle)
    @JvmStatic fun issueEpisode(source: String?, flag: String?, providerId: String?) = com.wuming.tv.core.catalog.DesktopCatalogSessions.issueEpisode(source, flag, providerId)
    @JvmStatic fun resolveEpisode(source: String?, flag: String?, handle: String?) = com.wuming.tv.core.catalog.DesktopCatalogSessions.resolveEpisode(source, flag, handle)
    @JvmStatic fun clear() = com.wuming.tv.core.catalog.DesktopCatalogSessions.clear()
}
