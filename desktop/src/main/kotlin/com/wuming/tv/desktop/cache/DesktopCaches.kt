package com.wuming.tv.desktop.cache

import com.wuming.tv.desktop.config.DesktopConfigurationCache
import com.wuming.tv.desktop.ui.ThumbnailDiskCache

internal object DesktopCaches {
    fun clear() {
        DesktopCacheGeneration.value.incrementAndGet()
        ResourceDiskCache.configurations.clear()
        ResourceDiskCache.playlists.clear()
        ResourceDiskCache.epg.clear()
        ResourceDiskCache.modules.clear()
        DesktopConfigurationCache().clear()
        ThumbnailDiskCache.openDefault().clear()
    }
}
