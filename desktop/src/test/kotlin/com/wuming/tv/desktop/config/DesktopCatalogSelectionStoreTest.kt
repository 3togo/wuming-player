package com.wuming.tv.desktop.config

import java.util.UUID
import java.util.prefs.Preferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DesktopCatalogSelectionStoreTest {
    private lateinit var preferences: Preferences

    @Before
    fun createNode() {
        preferences = Preferences.userRoot().node("tvbox-catalog-selection-test/${UUID.randomUUID()}")
    }

    @After
    fun removeNode() {
        preferences.removeNode()
        preferences.flush()
    }

    @Test
    fun `show all survives store recreation without impersonating a provider`() {
        DesktopCatalogSelectionStore(preferences).rememberShowAll()

        val restored = DesktopCatalogSelectionStore(preferences)
        assertTrue(restored.isShowAll(restored.rememberedSelection()))
        assertNull(restored.sourceKey(restored.rememberedSelection()))
    }

    @Test
    fun `provider key survives store recreation`() {
        DesktopCatalogSelectionStore(preferences).rememberSource("provider-zero")

        val restored = DesktopCatalogSelectionStore(preferences)
        assertFalse(restored.isShowAll(restored.rememberedSelection()))
        assertEquals("provider-zero", restored.sourceKey(restored.rememberedSelection()))
    }
}
