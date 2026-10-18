package com.wuming.tv.desktop.ui

import com.wuming.tv.desktop.config.DesktopCatalogSettings
import java.util.UUID
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogThumbnailSizingTest {
    @Test
    fun `thumbnail width scales with viewport and user preference`() {
        assertEquals(160, CatalogThumbnailSizing.cellWidth(1_000, 100))
        assertEquals(240, CatalogThumbnailSizing.cellWidth(1_500, 100))
        assertEquals(256, CatalogThumbnailSizing.cellWidth(1_000, 160))
        assertEquals(104, CatalogThumbnailSizing.cellWidth(1_000, 65))
    }

    @Test
    fun `thumbnail scale is persisted and constrained`() {
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
        try {
            val settings = DesktopCatalogSettings(preferences)
            assertEquals(DesktopCatalogSettings.DEFAULT_THUMBNAIL_SCALE, settings.thumbnailScalePercent)
            settings.thumbnailScalePercent = 135
            assertEquals(135, DesktopCatalogSettings(preferences).thumbnailScalePercent)
            settings.thumbnailScalePercent = 999
            assertEquals(DesktopCatalogSettings.MAX_THUMBNAIL_SCALE, settings.thumbnailScalePercent)
        } finally {
            preferences.removeNode()
        }
    }
}
