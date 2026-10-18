package com.wuming.tv.desktop.config

import com.wuming.tv.core.catalog.CatalogPagination
import java.util.prefs.Preferences

internal class DesktopCatalogSettings(private val preferences: Preferences) {
    var thumbnailsPerPage: Int
        get() = CatalogPagination.size(preferences.getInt("catalog.thumbnailsPerPage", CatalogPagination.DEFAULT_SIZE))
        set(value) {
            preferences.putInt("catalog.thumbnailsPerPage", CatalogPagination.size(value))
        }

    var thumbnailScalePercent: Int
        get() = preferences.getInt("catalog.thumbnailScalePercent", DEFAULT_THUMBNAIL_SCALE)
            .coerceIn(MIN_THUMBNAIL_SCALE, MAX_THUMBNAIL_SCALE)
        set(value) {
            preferences.putInt(
                "catalog.thumbnailScalePercent",
                value.coerceIn(MIN_THUMBNAIL_SCALE, MAX_THUMBNAIL_SCALE),
            )
        }

    companion object {
        const val MIN_THUMBNAIL_SCALE = 65
        const val MAX_THUMBNAIL_SCALE = 160
        const val DEFAULT_THUMBNAIL_SCALE = 100
    }
}
